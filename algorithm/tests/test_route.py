"""POST /api/v1/route 端点测试：坐标→坐标单路线（实时公交 ETA）。

覆盖：成功/不可达/降级/duration/distance/缓存/malformed 坐标/相同起终点/高并发。
外部 HTTP 以 _FakeAmapClient 模拟，不发真实请求。
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app import main
from app.distance import AmapDistanceProvider


@pytest.fixture(autouse=True)
def _no_real_sleep(monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setattr("app.distance.time.sleep", lambda _seconds: None)


class _FakeResponse:
    def __init__(self, payload: dict):
        self._payload = payload

    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return self._payload


class _FakeAmapClient:
    """模拟 httpx.Client：按 origins 数返回确定性距离/时长，可注入故障；calls 记录外部调用次数。"""

    def __init__(self, meters: int = 2800, seconds: int = 360, failure: str | None = None):
        self.meters = meters
        self.seconds = seconds
        self.failure = failure  # None | transport | status0 | result_error
        self.calls: list[dict] = []

    def get(self, url: str, params: dict | None = None) -> _FakeResponse:
        self.calls.append(params)
        if self.failure == "transport":
            raise httpx.ConnectError("connection refused")
        if self.failure == "status0":
            return _FakeResponse({"status": "0", "info": "DAILY_QUERY_OVER_LIMIT", "infocode": "10003"})
        origins = params["origins"].split("|")
        results = []
        for i in range(len(origins)):
            if self.failure == "result_error" and i == 0:
                results.append({"origin_id": "1", "dest_id": "1", "info": "未知错误", "code": "2"})
            else:
                results.append(
                    {"origin_id": str(i + 1), "dest_id": "1", "distance": str(self.meters), "duration": str(self.seconds)}
                )
        return _FakeResponse({"status": "1", "info": "OK", "infocode": "10000", "results": results})


def _payload(olat=30.5723, olon=104.0657, dlat=30.6012, dlon=104.1234) -> dict:
    return {
        "origin": {"latitude": olat, "longitude": olon},
        "destination": {"latitude": dlat, "longitude": dlon},
    }


def test_route_success_returns_amap_km_and_seconds(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _FakeAmapClient(meters=2800, seconds=360)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload())
    assert response.status_code == 200
    body = response.json()
    assert body["available"] is True
    assert body["distanceKm"] == 2.8        # 2800 米 → km
    assert body["durationSeconds"] == 360
    assert body["provider"] == "amap"


def test_route_duration_and_distance_correct(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _FakeAmapClient(meters=5000, seconds=600)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload())
    body = response.json()
    assert body["distanceKm"] == 5.0
    assert body["durationSeconds"] == 600


def test_route_unreachable_returns_available_false(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _FakeAmapClient(failure="result_error")
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload())
    body = response.json()
    assert body["available"] is False
    assert body["reasonCode"] == "ROUTE_UNAVAILABLE"
    assert body.get("distanceKm") is None
    assert body.get("durationSeconds") is None


@pytest.mark.parametrize("failure", ["transport", "status0"])
def test_route_failure_falls_back_euclidean(monkeypatch: pytest.MonkeyPatch, failure: str) -> None:
    client = _FakeAmapClient(failure=failure)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload())
    body = response.json()
    assert body["available"] is True
    assert body["provider"] == "euclidean"
    assert body["distanceKm"] > 0


def test_route_without_key_returns_euclidean(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main, "amap_provider", None)
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload())
    body = response.json()
    assert body["available"] is True
    assert body["provider"] == "euclidean"
    assert body["distanceKm"] > 0


def test_route_cache_reuses_matrix_without_extra_calls(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _FakeAmapClient(meters=2800, seconds=360)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        http.post("/api/v1/route", json=_payload())
        http.post("/api/v1/route", json=_payload())  # 同坐标 → 命中进程缓存
        http.post("/api/v1/route", json=_payload(olon=104.07))  # 坐标变了 → 新缓存键
    # 同坐标只调 1 次高德；不同坐标再调（get_matrix 每 destination 一次，2 点 → 2 次）
    assert client.calls and len(client.calls) == 4  # 首次 2 次 + 变坐标 2 次，缓存命中不新增


@pytest.mark.parametrize("lat,lon", [(-95.0, 104.0), (95.0, 104.0), (30.0, -190.0), (30.0, 190.0)])
def test_route_malformed_coordinates_rejected(monkeypatch: pytest.MonkeyPatch, lat: float, lon: float) -> None:
    monkeypatch.setattr(main, "amap_provider", None)
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload(olat=lat, olon=lon))
    assert response.status_code == 422  # Pydantic 范围校验


def test_route_same_origin_destination_distance_zero(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main, "amap_provider", None)
    with TestClient(main.app) as http:
        response = http.post("/api/v1/route", json=_payload(dlat=30.5723, dlon=104.0657))
    body = response.json()
    assert body["available"] is True
    assert body["distanceKm"] == 0
    assert body["durationSeconds"] == 0


def test_route_high_concurrency_consistent(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _FakeAmapClient(meters=2800, seconds=360)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    payload = _payload()
    with TestClient(main.app) as http:
        import concurrent.futures

        def call(_):
            return http.post("/api/v1/route", json=payload)

        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
            responses = list(pool.map(call, range(5)))
    assert all(r.status_code == 200 for r in responses)
    bodies = [r.json() for r in responses]
    assert all(b["available"] is True and b["distanceKm"] == 2.8 for b in bodies)
