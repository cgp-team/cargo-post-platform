"""POST /api/v1/distance 端点测试：单路线距离/时长、provider 标记、不可达、降级、参数校验。

外部 HTTP 以 _FakeAmapClient 模拟，不发真实请求（与 test_distance.py 同风格）。
统一口径：distanceUnit 恒为 km（高德路网 / 直线估算均不重复换算），provider 区分 amap / euclidean。
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app import main
from app.distance import AmapDistanceProvider


@pytest.fixture(autouse=True)
def _no_real_sleep(monkeypatch: pytest.MonkeyPatch):
    """限速/退避的 time.sleep 在测试中统一置空。"""
    monkeypatch.setattr("app.distance.time.sleep", lambda _seconds: None)


class _FakeResponse:
    def __init__(self, payload: dict):
        self._payload = payload

    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return self._payload


class _FakeAmapClient:
    """模拟 httpx.Client：按 origins 个数返回确定性距离/时长，可注入故障。

    failure: None | transport | status0 | result_error
    - result_error：单项返回 info/code（无可行车道路）→ 不可达（available=false）
    """

    def __init__(self, meters: int = 10000, seconds: int = 600, failure: str | None = None):
        self.meters = meters
        self.seconds = seconds
        self.failure = failure
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


def _payload(request_id: str = "req-dist-1") -> dict:
    return {
        "requestId": request_id,
        "stations": [
            {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
            {"stationId": "S2", "longitude": 104.020, "latitude": 30.020},
        ],
    }


def test_distance_with_key_returns_amap_km(monkeypatch: pytest.MonkeyPatch) -> None:
    client = _FakeAmapClient(meters=20000, seconds=1200)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/distance", json=_payload())
    assert response.status_code == 200
    body = response.json()
    assert body["distanceUnit"] == "km"
    assert len(body["pairs"]) == 1
    pair = body["pairs"][0]
    assert pair["fromStationId"] == "S1"
    assert pair["toStationId"] == "S2"
    assert pair["available"] is True
    assert pair["provider"] == "amap"
    assert pair["distanceKm"] == 20.0
    assert pair["durationSeconds"] == 1200.0


def test_distance_without_key_returns_euclidean_km(monkeypatch: pytest.MonkeyPatch) -> None:
    """未配置 AMAP_KEY：直线估算（Haversine 公里 + 均速秒），provider=euclidean，distanceUnit=km。"""
    monkeypatch.setattr(main, "amap_provider", None)
    with TestClient(main.app) as http:
        response = http.post("/api/v1/distance", json=_payload())
    assert response.status_code == 200
    body = response.json()
    assert body["distanceUnit"] == "km"
    pair = body["pairs"][0]
    assert pair["available"] is True
    assert pair["provider"] == "euclidean"
    assert pair["distanceKm"] > 0
    assert pair["durationSeconds"] is not None


@pytest.mark.parametrize("failure", ["transport", "status0"])
def test_distance_amap_failure_falls_back_euclidean(monkeypatch: pytest.MonkeyPatch, failure: str) -> None:
    client = _FakeAmapClient(failure=failure)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/distance", json=_payload())
    assert response.status_code == 200
    pair = response.json()["pairs"][0]
    assert pair["provider"] == "euclidean"
    assert pair["available"] is True


def test_distance_unreachable_available_false(monkeypatch: pytest.MonkeyPatch) -> None:
    """高德明确无可行车道路（单项 info/code 错误）→ available=false，无距离/时长。"""
    client = _FakeAmapClient(failure="result_error")
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    with TestClient(main.app) as http:
        response = http.post("/api/v1/distance", json=_payload())
    assert response.status_code == 200
    pair = response.json()["pairs"][0]
    assert pair["available"] is False
    assert pair["provider"] == "amap"
    # response_model_exclude_none：不可达无距离/时长，字段被省略
    assert pair.get("distanceKm") is None
    assert pair.get("durationSeconds") is None


def test_distance_rejects_wrong_station_count() -> None:
    with TestClient(main.app) as http:
        response = http.post(
            "/api/v1/distance",
            json={"requestId": "req-dist-2", "stations": [{"stationId": "S1", "longitude": 104.0, "latitude": 30.0}]},
        )
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_INPUT"
