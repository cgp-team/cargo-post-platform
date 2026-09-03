"""Baseline 对比测试：Nearest Neighbor / Greedy VRP vs 当前算法。

同一输入运行 Baseline 和 Current Algorithm，记录：
  - distance
  - vehicle_count
  - runtime

输出 distance_improvement_percent 和 vehicle_reduction_percent。
当前算法反而更差时也必须诚实输出。
"""

from __future__ import annotations

import math
import time
from typing import NamedTuple

from app.models import (
    OrderType,
    PlanOrder,
    PlanRequest,
    Station,
    StopAction,
    Vehicle,
)
from app.solver import solve


class Point(NamedTuple):
    lon: float
    lat: float


def _dist(a: Point, b: Point) -> float:
    return math.hypot(a.lon - b.lon, a.lat - b.lat)


def _nearest_neighbor_tsp(depot: Point, points: list[Point]) -> float:
    """Nearest Neighbor 启发式 TSP，返回总距离。"""
    if not points:
        return 0.0
    visited = [False] * len(points)
    current = depot
    total = 0.0
    for _ in range(len(points)):
        best_idx = -1
        best_dist = float("inf")
        for j, pt in enumerate(points):
            if not visited[j]:
                d = _dist(current, pt)
                if d < best_dist:
                    best_dist = d
                    best_idx = j
        visited[best_idx] = True
        total += best_dist
        current = points[best_idx]
    total += _dist(current, depot)
    return total


def _build_stations_and_points(n: int):
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.02, latitude=30.0 + i * 0.02)
        for i in range(n)
    ]
    points = [Point(104.0 + i * 0.02, 30.0 + i * 0.02) for i in range(n)]
    return stations, points


def _run_algorithm(req: PlanRequest) -> dict:
    start = time.monotonic()
    outcome = solve(req)
    elapsed_ms = (time.monotonic() - start) * 1000
    return {
        "status": outcome.status,
        "distance": outcome.total_distance,
        "vehicle_count": len(outcome.vehicle_plans),
        "runtime_ms": round(elapsed_ms, 1),
    }


# ═══════════════════════════════════════════════════════════════
# 测试用例
# ═══════════════════════════════════════════════════════════════


def test_baseline_delivery_5_stops():
    """5 个派送站：Baseline (NN) vs Algorithm。"""
    stations, points = _build_stations_and_points(6)
    depot_pt = points[0]
    task_points = points[1:]

    orders = [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{i+1}", itemCount=1)
        for i in range(5)
    ]
    req = PlanRequest(
        requestId="baseline-delivery-5",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=orders,
    )

    baseline_dist = _nearest_neighbor_tsp(depot_pt, task_points)
    algo = _run_algorithm(req)

    assert algo["status"] == "feasible"
    algo_dist = algo["distance"]

    dist_improvement = ((baseline_dist - algo_dist) / baseline_dist * 100) if baseline_dist > 0 else 0

    print(f"BASELINE_DISTANCE={baseline_dist:.4f}")
    print(f"ALGORITHM_DISTANCE={algo_dist:.4f}")
    print(f"ALGORITHM_RUNTIME_MS={algo['runtime_ms']}")
    print(f"distance_improvement_percent={dist_improvement:.1f}%")
    # 不伪造优势：如果算法更差也输出
    if dist_improvement < 0:
        print(f"WARNING: Algorithm is {-dist_improvement:.1f}% WORSE than baseline")


def test_baseline_passenger_6_stops():
    """6 名乘客（需要 2 车）：Baseline vs Algorithm。"""
    stations, points = _build_stations_and_points(4)
    depot_pt = points[0]
    # 每个乘客有上车+下车两个节点
    task_points = [points[1], points[2], points[1], points[2], points[1], points[2],
                   points[1], points[2], points[1], points[2], points[1], points[2]]

    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2")
        for i in range(6)
    ]
    vehicles = [Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 3)]
    req = PlanRequest(
        requestId="baseline-passenger-6",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=vehicles,
        orders=orders,
    )

    # Baseline: 单车 NN（不考虑容量）
    baseline_dist = _nearest_neighbor_tsp(depot_pt, [points[1], points[2]])
    algo = _run_algorithm(req)

    assert algo["status"] == "feasible"
    algo_dist = algo["distance"]
    algo_vehicles = algo["vehicle_count"]

    dist_improvement = ((baseline_dist - algo_dist) / baseline_dist * 100) if baseline_dist > 0 else 0

    print(f"BASELINE_DISTANCE={baseline_dist:.4f} (NN, no capacity)")
    print(f"ALGORITHM_DISTANCE={algo_dist:.4f}")
    print(f"ALGORITHM_VEHICLE_COUNT={algo_vehicles}")
    print(f"ALGORITHM_RUNTIME_MS={algo['runtime_ms']}")
    print(f"distance_improvement_percent={dist_improvement:.1f}%")
    if dist_improvement < 0:
        print(f"WARNING: Algorithm is {-dist_improvement:.1f}% WORSE than baseline")


def test_baseline_mixed_scenario():
    """混合场景：乘客 + 派送 + 揽收。"""
    stations, points = _build_stations_and_points(6)
    depot_pt = points[0]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3"),
        PlanOrder(orderId="P2", orderType=OrderType.PASSENGER,
                  boardingStationId="S2", alightingStationId="S4"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S5", itemCount=1),
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S3", itemCount=1),
    ]
    req = PlanRequest(
        requestId="baseline-mixed",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=orders,
    )

    # Baseline NN over task nodes (deduplicated)
    task_points = [points[1], points[2], points[3], points[4], points[5]]
    baseline_dist = _nearest_neighbor_tsp(depot_pt, task_points)
    algo = _run_algorithm(req)

    assert algo["status"] == "feasible"
    algo_dist = algo["distance"]

    dist_improvement = ((baseline_dist - algo_dist) / baseline_dist * 100) if baseline_dist > 0 else 0

    print(f"BASELINE_DISTANCE={baseline_dist:.4f}")
    print(f"ALGORITHM_DISTANCE={algo_dist:.4f}")
    print(f"ALGORITHM_RUNTIME_MS={algo['runtime_ms']}")
    print(f"distance_improvement_percent={dist_improvement:.1f}%")
    if dist_improvement < 0:
        print(f"WARNING: Algorithm is {-dist_improvement:.1f}% WORSE than baseline")
