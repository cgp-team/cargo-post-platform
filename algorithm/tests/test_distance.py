"""距离提供方与高德路网接入测试：矩阵映射、缓存、降级、单位口径、无 key 兼容。

外部 HTTP 全部以 FakeAmapClient 模拟，不发真实请求。
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from app import main
from app.distance import (
    AmapDistanceProvider,
    AmapUnavailable,
    EuclideanDistanceProvider,
)
from app.models import OrderType, PlanOrder, PlanRequest, Station, Vehicle
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId="S1", longitude=104.010, latitude=30.010),
    Station(stationId="S2", longitude=104.020, latitude=30.020),
    Station(stationId="S3", longitude=104.030, latitude=30.030),
]
POINTS = [DEPOT, *STATIONS]


class FakeResponse:
    def __init__(self, payload: dict):
        self._payload = payload

    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return self._payload


class FakeAmapClient:
    """模拟 httpx.Client：按 origins 个数生成确定性响应，可注入故障；calls 记录外部调用。"""

    def __init__(self, meters: int = 10000, seconds: int = 600, failure: str | None = None):
        self.meters = meters
        self.seconds = seconds
        self.failure = failure  # None | transport | status0 | result_error | flaky
        self.calls: list[dict] = []

    def get(self, url: str, params: dict | None = None) -> FakeResponse:
        self.calls.append(params)
        origins = params["origins"].split("|")
        if self.failure == "flaky" and len(self.calls) == 1:
            raise httpx.ConnectError("connection refused")
        if self.failure == "transport":
            raise httpx.ConnectError("connection refused")
        if self.failure == "timeout":
            raise httpx.ReadTimeout("read timed out")
        if self.failure == "status0":
            return FakeResponse({"status": "0", "info": "DAILY_QUERY_OVER_LIMIT", "infocode": "10003"})
        results = []
        for index, _origin in enumerate(origins):
            if self.failure == "result_error" and index == 0:
                # 官方文档：单项出错时返回 info/code（1 无道路 / 2 离道路过远 / 3 不在中国境内）
                results.append({"origin_id": "1", "dest_id": "1", "info": "未知错误", "code": "2"})
            else:
                results.append(
                    {
                        "origin_id": str(index + 1),
                        "dest_id": "1",
                        "distance": str(self.meters),
                        "duration": str(self.seconds),
                    }
                )
        return FakeResponse({"status": "1", "info": "OK", "infocode": "10000", "results": results})


def make_request(orders: list[PlanOrder] | None = None, request_id: str = "req-distance") -> PlanRequest:
    if orders is None:
        orders = [
            PlanOrder(orderId="O-P1", orderType=OrderType.PASSENGER, boardingStationId="S1", alightingStationId="S2"),
            PlanOrder(orderId="O-D1", orderType=OrderType.DELIVERY, stationId="S3", itemCount=1),
        ]
    return PlanRequest(
        requestId=request_id,
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T08:30:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=1001)],
        orders=orders,
    )


def make_payload(request_id: str) -> dict:
    return {
        "requestId": request_id,
        "batchStart": "2026-08-23T08:00:00+08:00",
        "batchEnd": "2026-08-23T08:30:00+08:00",
        "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
        "stations": [
            {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
            {"stationId": "S2", "longitude": 104.020, "latitude": 30.020},
            {"stationId": "S3", "longitude": 104.030, "latitude": 30.030},
        ],
        "vehicles": [{"vehicleId": 1001, "passengerCapacity": 5, "cargoCapacity": 4}],
        "orders": [
            {"orderId": "O-P1", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
            {"orderId": "O-D1", "orderType": "DELIVERY", "stationId": "S3", "itemCount": 1},
        ],
    }


def test_from_env_without_key_returns_none(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AMAP_KEY", raising=False)
    assert AmapDistanceProvider.from_env() is None
    monkeypatch.setenv("AMAP_KEY", "   ")
    assert AmapDistanceProvider.from_env() is None
    monkeypatch.setenv("AMAP_KEY", "test-key")
    assert AmapDistanceProvider.from_env() is not None


def test_amap_matrix_mapped_to_km() -> None:
    client = FakeAmapClient(meters=12345, seconds=678)
    provider = AmapDistanceProvider(key="test-key", client=client)
    matrix = provider.get_matrix(POINTS)
    # 米换算公里，duration 保留秒；双向站点对全覆盖
    assert matrix[("S0", "S1")] == (12.345, 678.0)
    assert matrix[("S1", "S0")] == (12.345, 678.0)
    assert matrix[("S3", "S2")] == (12.345, 678.0)
    assert len(matrix) == len(POINTS) * len(POINTS)
    # 每个 destination 一次外部调用
    assert len(client.calls) == len(POINTS)
    for call in client.calls:
        assert call["type"] == "1"
        assert call["key"] == "test-key"
        assert len(call["origins"].split("|")) == len(POINTS)


def test_cache_hit_skips_external_calls() -> None:
    client = FakeAmapClient()
    provider = AmapDistanceProvider(key="test-key", client=client)
    first = provider.get_matrix(POINTS)
    # 同站点集合（含乱序重建）命中缓存：0 次新增外部调用
    shuffled = list(reversed(POINTS))
    second = provider.get_matrix(shuffled)
    assert second == first
    assert len(client.calls) == len(POINTS)


def test_cache_expired_refetches(monkeypatch: pytest.MonkeyPatch) -> None:
    client = FakeAmapClient()
    provider = AmapDistanceProvider(key="test-key", client=client)
    provider.get_matrix(POINTS)
    entry = next(iter(provider._cache.values()))
    entry.created_at -= 24 * 3600 + 1  # 超过 TTL
    provider.get_matrix(POINTS)
    assert len(client.calls) == 2 * len(POINTS)


def test_flaky_retry_succeeds_on_second_attempt() -> None:
    client = FakeAmapClient(failure="flaky")
    provider = AmapDistanceProvider(key="test-key", client=client)
    matrix = provider.get_matrix(POINTS)
    assert matrix[("S0", "S1")] == (10.0, 600.0)
    # 首次 destination 调用失败 1 次 + 重试成功，其余 destination 各 1 次
    assert len(client.calls) == len(POINTS) + 1


@pytest.mark.parametrize("failure", ["transport", "timeout", "status0", "result_error"])
def test_amap_failure_raises_unavailable(failure: str) -> None:
    client = FakeAmapClient(failure=failure)
    provider = AmapDistanceProvider(key="test-key", client=client)
    with pytest.raises(AmapUnavailable):
        provider.get_matrix(POINTS)
    # 首个 destination 重试 1 次仍失败即整单放弃（每 destination 最多 2 次尝试）
    assert len(client.calls) == 2


def test_solve_with_matrix_uses_km() -> None:
    provider = AmapDistanceProvider(key="test-key", client=FakeAmapClient(meters=10000))
    matrix = provider.get_matrix(POINTS)
    outcome = solve(make_request(), matrix)
    assert outcome.status == "feasible"
    for plan in outcome.vehicle_plans:
        arcs = len(plan.stops) - 1  # DEPART 之后每站一段弧
        assert plan.totalDistance == 10.0 * arcs
        assert all(stop.segmentDistance == 10.0 for stop in plan.stops[1:])


def test_euclidean_provider_matches_builtin() -> None:
    """欧氏 provider 矩阵注入与内置 hypot 路径结果完全一致（口径兼容）。"""
    request = make_request()
    matrix = EuclideanDistanceProvider().get_matrix(POINTS)
    injected = solve(request, matrix)
    builtin = solve(request)
    assert injected.status == builtin.status == "feasible"
    assert injected.total_distance == builtin.total_distance
    assert [p.model_dump() for p in injected.vehicle_plans] == [p.model_dump() for p in builtin.vehicle_plans]


def test_build_result_with_key_returns_km_unit(monkeypatch: pytest.MonkeyPatch) -> None:
    client = FakeAmapClient(meters=10000)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    result = main.build_result(make_request())
    assert result.status == "feasible"
    assert result.distanceUnit == "km"
    assert result.algorithmVersion == "ortools-1.1.0"
    assert result.parameterVersion == "params-v2"
    assert not any("降级" in warning for warning in result.warnings)
    for plan in result.vehiclePlans:
        assert plan.totalDistance == 10.0 * (len(plan.stops) - 1)


def test_build_result_without_key_unchanged(monkeypatch: pytest.MonkeyPatch) -> None:
    """无 key 时行为与现状一致：欧氏直线（度），无降级 warning。"""
    monkeypatch.setattr(main, "amap_provider", None)
    request = make_request()
    result = main.build_result(request)
    assert result.distanceUnit == "degree"
    assert result.warnings == []
    assert result.totalDistance == solve(request).total_distance


@pytest.mark.parametrize("failure", ["transport", "timeout", "status0", "result_error"])
def test_build_result_amap_failure_falls_back(monkeypatch: pytest.MonkeyPatch, failure: str) -> None:
    client = FakeAmapClient(failure=failure)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    request = make_request()
    result = main.build_result(request)
    assert result.status == "feasible"
    assert result.distanceUnit == "degree"
    assert "路网距离不可用，已降级直线距离" in result.warnings
    assert result.totalDistance == solve(request).total_distance


def test_plan_endpoint_with_key_returns_km_unit(monkeypatch: pytest.MonkeyPatch) -> None:
    client = FakeAmapClient(meters=10000)
    monkeypatch.setattr(main, "amap_provider", AmapDistanceProvider(key="test-key", client=client))
    main.jobs.clear()
    with TestClient(main.app) as http:
        response = http.post("/api/v1/plan", json=make_payload("req-amap-km"))
    assert response.status_code == 200
    body = response.json()
    assert body["distanceUnit"] == "km"
    assert body["algorithmVersion"] == "ortools-1.1.0"
    assert body["parameterVersion"] == "params-v2"


def test_plan_endpoint_without_key_returns_degree_unit(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(main, "amap_provider", None)
    main.jobs.clear()
    with TestClient(main.app) as http:
        response = http.post("/api/v1/plan", json=make_payload("req-euclidean"))
    assert response.status_code == 200
    body = response.json()
    assert body["distanceUnit"] == "degree"
    assert body["warnings"] == []
