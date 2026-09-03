"""HACO-CPS 可复现性测试：相同种子 → 相同结果。"""

from __future__ import annotations

from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    Station,
    Vehicle,
)
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId="S1", longitude=104.010, latitude=30.010),
    Station(stationId="S2", longitude=104.020, latitude=30.020),
    Station(stationId="S3", longitude=104.030, latitude=30.030),
]


def _make_request(seed: int = 20260903) -> PlanRequest:
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    return PlanRequest(
        requestId="repro-test",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
        algorithmConfig=AlgorithmConfig(
            algorithmMode=AlgorithmMode.HACO,
            randomSeed=seed,
            ant_count=8,
            max_iterations=10,
        ),
    )


def test_same_seed_same_result():
    """相同种子 20 次运行结果必须一致。"""
    base = solve(_make_request(seed=20260903))
    assert base.status == "feasible"

    for i in range(20):
        result = solve(_make_request(seed=20260903))
        assert result.status == base.status, f"第 {i+1} 次状态不一致"
        assert result.total_distance == base.total_distance, f"第 {i+1} 次距离不一致"
        assert len(result.vehicle_plans) == len(base.vehicle_plans), f"第 {i+1} 次车辆数不一致"


def test_different_seed_may_differ():
    """不同种子可能产生不同结果（但不一定）。"""
    results = set()
    for seed in [100, 200, 300, 400, 500]:
        result = solve(_make_request(seed=seed))
        assert result.status == "feasible"
        results.add(result.total_distance)

    # 至少记录不同距离值（可能全部相同如果场景太简单）
    # 这里只验证不崩溃
    assert len(results) >= 1
