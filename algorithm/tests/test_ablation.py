"""HACO-CPS 消融实验：五档比较。

A: BASELINE (OR-Tools 1.3.0)
B: ACO_ONLY (basic pheromone + distance heuristic)
C: ACO_HEURISTIC (+ passenger impact + cargo detour + skeleton + time risk)
D: ACO_HEURISTIC_LOCAL (+ local search)
E: HACO_FULL (+ LNS + adaptive operators)
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


def _make_request(mode: AlgorithmMode, seed: int = 20260903, **config_kwargs) -> PlanRequest:
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i % 5) + 1}", alightingStationId=f"S{((i + 2) % 5) + 1}")
        for i in range(5)
    ] + [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{(i % 5) + 1}", itemCount=1)
        for i in range(3)
    ]
    defaults = dict(algorithmMode=mode, randomSeed=seed, ant_count=8, max_iterations=10)
    defaults.update(config_kwargs)
    config = AlgorithmConfig(**defaults)
    return PlanRequest(
        requestId=f"ablation-{mode.value}",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 3)],
        orders=orders,
        algorithmConfig=config,
    )


def test_a_baseline():
    """A: BASELINE 模式。"""
    result = solve(_make_request(AlgorithmMode.BASELINE))
    assert result.status == "feasible"
    assert result.algorithm_version == "ortools-1.3.0"


def test_b_haco_basic():
    """B: HACO 基础模式（默认参数）。"""
    result = solve(_make_request(AlgorithmMode.HACO))
    assert result.status == "feasible"
    assert "haco-cps" in result.algorithm_version


def test_c_haco_with_heuristic_weights():
    """C: HACO + 业务启发式权重。"""
    result = solve(_make_request(
        AlgorithmMode.HACO,
        w_distance=0.3,
        w_passenger_impact=0.25,
        w_detour=0.2,
        w_time_risk=0.15,
        w_skeleton_penalty=0.1,
    ))
    assert result.status == "feasible"


def test_d_haco_with_local_search():
    """D: HACO + 局部搜索。"""
    result = solve(_make_request(
        AlgorithmMode.HACO,
        local_search_rounds=3,
    ))
    assert result.status == "feasible"


def test_e_haco_full():
    """E: HACO 完整模式（LNS + 自适应参数）。"""
    result = solve(_make_request(
        AlgorithmMode.HACO,
        lns_probability=0.5,
        destroy_fraction=0.3,
        local_search_rounds=2,
        ant_count=12,
        max_iterations=15,
    ))
    assert result.status == "feasible"


def test_ablation_comparison():
    """五档比较：记录所有指标。"""
    results = {}

    # A: Baseline
    a = solve(_make_request(AlgorithmMode.BASELINE))
    results["BASELINE"] = {
        "vehicles": len(a.vehicle_plans),
        "distance": a.total_distance,
        "version": a.algorithm_version,
        "status": a.status,
    }

    # B: ACO basic
    b = solve(_make_request(AlgorithmMode.HACO, ant_count=4, max_iterations=5))
    results["ACO_BASIC"] = {
        "vehicles": len(b.vehicle_plans),
        "distance": b.total_distance,
        "version": b.algorithm_version,
        "status": b.status,
    }

    # C: ACO + heuristic
    c = solve(_make_request(AlgorithmMode.HACO, w_passenger_impact=0.3, w_detour=0.2))
    results["ACO_HEURISTIC"] = {
        "vehicles": len(c.vehicle_plans),
        "distance": c.total_distance,
        "version": c.algorithm_version,
        "status": c.status,
    }

    # D: ACO + local search
    d = solve(_make_request(AlgorithmMode.HACO, local_search_rounds=3))
    results["ACO_LOCAL"] = {
        "vehicles": len(d.vehicle_plans),
        "distance": d.total_distance,
        "version": d.algorithm_version,
        "status": d.status,
    }

    # E: HACO full
    e = solve(_make_request(AlgorithmMode.HACO, lns_probability=0.5, ant_count=12, max_iterations=15))
    results["HACO_FULL"] = {
        "vehicles": len(e.vehicle_plans),
        "distance": e.total_distance,
        "version": e.algorithm_version,
        "status": e.status,
    }

    # 打印结果
    for name, r in results.items():
        print(f"{name}: vehicles={r['vehicles']}, distance={r['distance']:.3f}, status={r['status']}")

    # 所有模式都应该可行
    for name, r in results.items():
        assert r["status"] == "feasible", f"{name} should be feasible"
