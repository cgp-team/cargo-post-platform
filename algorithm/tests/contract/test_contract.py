"""路线规划算法服务契约验收测试（复刻自 mock-algorithm/tests/contract，逐条保持一致）。

用途：对任意声称实现契约（docs/api/algorithm-api.yaml）的算法服务做接入验收。

用法：
    # 验收部署中的本服务（uvicorn 起在端口上）
    ALGORITHM_BASE_URL=http://127.0.0.1:<port> pytest tests/contract -q

    # 未设置环境变量时回退到本服务进程内 TestClient 自验（CI 默认路径）
    pytest tests/contract -q

注意：scenario 驱动的混沌用例（TIMEOUT / NO_FEASIBLE_SOLUTION 等）为 Mock 专属，
保留在 mock-algorithm/tests/test_main.py，不属于本契约套件。
"""

from __future__ import annotations

import os
import uuid

import httpx
import pytest

STATIONS = [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020},
    {"stationId": "S3", "longitude": 104.030, "latitude": 30.030},
]


def make_payload(request_id: str, orders: list[dict], vehicle_count: int = 1) -> dict:
    """构造契约合规的规划请求；坐标为真实经纬度量级，避免实现对坐标范围敏感。"""
    return {
        "requestId": request_id,
        "batchStart": "2026-08-23T08:00:00+08:00",
        "batchEnd": "2026-08-23T18:00:00+08:00",
        "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
        "stations": STATIONS,
        "vehicles": [
            {"vehicleId": 1000 + index, "passengerCapacity": 5, "cargoCapacity": 4}
            for index in range(1, vehicle_count + 1)
        ],
        "orders": orders,
    }


def passenger_order(index: int) -> dict:
    return {
        "orderId": f"O-P{index}",
        "orderType": "PASSENGER",
        "boardingStationId": "S1",
        "alightingStationId": "S2",
    }


def cargo_order(index: int, order_type: str = "DELIVERY") -> dict:
    return {
        "orderId": f"O-{order_type[0]}{index}",
        "orderType": order_type,
        "stationId": "S3",
        "itemCount": 1,
        "weightKg": 10,
        "volumeM3": 0.1,
    }


@pytest.fixture(scope="session")
def client():
    """ALGORITHM_BASE_URL 指向真实服务时走网络；否则回退 Mock 进程内自验。"""
    base_url = os.environ.get("ALGORITHM_BASE_URL")
    if base_url:
        with httpx.Client(base_url=base_url.rstrip("/"), timeout=30.0) as http_client:
            yield http_client
    else:
        from fastapi.testclient import TestClient

        from app.main import app, jobs

        jobs.clear()
        with TestClient(app) as test_client:
            yield test_client


def unique_id(prefix: str) -> str:
    """每次运行生成全局唯一 requestId，避免命中服务端 24h 幂等缓存干扰断言。"""
    return f"{prefix}-{uuid.uuid4().hex[:12]}"


def test_health(client) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert "algorithmVersion" in data
    # 验证 HACO-CPS 版本
    assert "haco-cps" in data["algorithmVersion"] or "ortools" in data["algorithmVersion"]


def test_ready(client) -> None:
    response = client.get("/ready")
    assert response.status_code == 200


def test_feasible_closed_loop(client) -> None:
    payload = make_payload(
        unique_id("feasible"),
        [passenger_order(1), cargo_order(1), cargo_order(2, "PICKUP")],
    )
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 200
    result = response.json()
    assert result["status"] == "feasible"
    assert result["vehiclePlans"], "可行解必须给出车辆方案"
    for plan in result["vehiclePlans"]:
        stops = plan["stops"]
        assert stops[0]["action"] == "DEPART", "闭环必须从 DEPART 开始"
        assert stops[-1]["action"] == "RETURN", "闭环必须以 RETURN 结束"
        actions = [stop["action"] for stop in stops]
        assert actions.index("BOARD") < actions.index("ALIGHT"), "客运必须先上车后下车"


def test_over_capacity_infeasible(client) -> None:
    # 6 名乘客 > 单车 5 人硬约束（契约：载客 ≤ 5），必须判无解并带原因码
    payload = make_payload(unique_id("capacity"), [passenger_order(i) for i in range(6)])
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 200
    result = response.json()
    assert result["status"] == "infeasible"
    assert result["reasonCode"] == "OVER_CAPACITY"


def test_over_limit_413(client) -> None:
    # 26 张订单 > 契约 25 单上限
    payload = make_payload(
        unique_id("limit"), [passenger_order(i) for i in range(26)], vehicle_count=3
    )
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 413


def test_idempotent_replay_returns_cached(client) -> None:
    payload = make_payload(unique_id("idempotent"), [passenger_order(1)])
    first = client.post("/api/v1/plan", json=payload)
    assert first.status_code == 200
    second = client.post("/api/v1/plan", json=payload)
    assert second.status_code == 200
    assert second.json()["cached"] is True
    assert second.json()["vehiclePlans"] == first.json()["vehiclePlans"]


def test_unknown_station_400(client) -> None:
    payload = make_payload(unique_id("unknown"), [passenger_order(1)])
    payload["orders"][0]["boardingStationId"] = "S99"
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 400


def test_scenario_field_tolerated(client) -> None:
    # scenario 为 Mock 专属字段（契约标注"真实算法可忽略"）：携带时不得导致 400/422
    payload = make_payload(unique_id("scenario"), [passenger_order(1)])
    payload["scenario"] = "SUCCESS"
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 200
    assert response.json()["status"] == "feasible"
