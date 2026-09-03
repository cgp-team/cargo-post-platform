"""HACO-CPS 消融实验：比较不同算法模式的效果。

A: BASELINE (OR-Tools)
B: HACO (full)
"""

from __future__ import annotations

from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    Vehicle,
)
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
    for i in range(1, 6)
]


def _make_request(mode: AlgorithmMode, seed: int = 20260903) -> PlanRequest:
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i % 5) + 1}", alightingStationId=f"S{((i + 2) % 5) + 1}")
        for i in range(5)
    ] + [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{(i % 5) + 1}", itemCount=1)
        for i in range(3)
    ]
    return PlanRequest(
        requestId=f"ablation-{mode.value}",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 3)],
        orders=orders,
        algorithmConfig=AlgorithmConfig(
            algorithmMode=mode,
            randomSeed=seed,
            ant_count=8,
            max_iterations=10,
        ),
    )


def test_baseline_mode():
    """A: BASELINE 模式。"""
    result = solve(_make_request(AlgorithmMode.BASELINE))
    assert result.status == "feasible"
    assert result.algorithm_version == "ortools-1.3.0"


def test_haco_mode():
    """B: HACO 模式。"""
    result = solve(_make_request(AlgorithmMode.HACO))
    assert result.status == "feasible"
    assert result.algorithm_version == "haco-cps-1.0.0"


def test_haco_not_worse_than_baseline():
    """HACO 不应该比 baseline 差（在大多数场景）。"""
    baseline = solve(_make_request(AlgorithmMode.BASELINE))
    haco = solve(_make_request(AlgorithmMode.HACO))

    assert baseline.status == "feasible"
    assert haco.status == "feasible"

    # 车辆数应该相同或更好
    assert len(haco.vehicle_plans) <= len(baseline.vehicle_plans)

    # 打印比较结果
    print(f"BASELINE: vehicles={len(baseline.vehicle_plans)}, distance={baseline.total_distance:.3f}")
    print(f"HACO:     vehicles={len(haco.vehicle_plans)}, distance={haco.total_distance:.3f}")

    improvement = 0
    if baseline.total_distance > 0:
        improvement = (baseline.total_distance - haco.total_distance) / baseline.total_distance * 100
    print(f"Distance improvement: {improvement:.1f}%")
