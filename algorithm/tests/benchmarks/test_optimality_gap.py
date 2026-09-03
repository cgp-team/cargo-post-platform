"""小规模最优性 Gap 测试。

构造超小场景（3~6 个任务节点），用穷举求真正最优结果，
与当前 OR-Tools solver 比较，计算 optimality_gap。

公式：gap = (algorithm_distance - optimal_distance) / optimal_distance

小规模场景要求 gap <= 10%，更简单场景要求 <= 5%。
"""

from __future__ import annotations

import itertools
import math
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
    """欧氏距离（度）。"""
    return math.hypot(a.lon - b.lon, a.lat - b.lat)


def _exhaustive_tsp_distance(depot: Point, points: list[Point]) -> float:
    """穷举所有排列，返回最短 Hamilton 回路距离。"""
    if not points:
        return 0.0
    best = float("inf")
    for perm in itertools.permutations(points):
        d = _dist(depot, perm[0])
        for i in range(len(perm) - 1):
            d += _dist(perm[i], perm[i + 1])
        d += _dist(perm[-1], depot)
        best = min(best, d)
    return best


def _build_stations(n: int) -> list[Station]:
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.02, latitude=30.0 + i * 0.02)
        for i in range(n)
    ]


def _algorithm_distance(req: PlanRequest) -> float:
    outcome = solve(req)
    assert outcome.status == "feasible", f"应可行: {outcome.reason_code}"
    return outcome.total_distance


# ═══════════════════════════════════════════════════════════════
# 测试用例
# ═══════════════════════════════════════════════════════════════


def test_gap_single_delivery_3_stops():
    """3 个派送站，穷举最优 vs 算法。gap <= 5%。"""
    stations = _build_stations(4)  # S0=depot, S1, S2, S3
    depot_pt = Point(104.0, 30.0)
    pts = [Point(104.02, 30.02), Point(104.04, 30.04), Point(104.06, 30.06)]

    orders = [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{i+1}", itemCount=1)
        for i in range(3)
    ]
    req = PlanRequest(
        requestId="gap-3",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    optimal = _exhaustive_tsp_distance(depot_pt, pts)
    algo = _algorithm_distance(req)

    gap = (algo - optimal) / optimal if optimal > 0 else 0
    print(f"OPTIMAL_DISTANCE={optimal:.4f}")
    print(f"ALGORITHM_DISTANCE={algo:.4f}")
    print(f"OPTIMALITY_GAP={gap:.4f} ({gap*100:.1f}%)")
    assert gap <= 0.05, f"gap {gap:.4f} > 5%"


def test_gap_single_delivery_4_stops():
    """4 个派送站。gap <= 10%。"""
    stations = _build_stations(5)
    depot_pt = Point(104.0, 30.0)
    pts = [Point(104.02, 30.02), Point(104.04, 30.04),
           Point(104.06, 30.06), Point(104.08, 30.08)]

    orders = [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{i+1}", itemCount=1)
        for i in range(4)
    ]
    req = PlanRequest(
        requestId="gap-4",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    optimal = _exhaustive_tsp_distance(depot_pt, pts)
    algo = _algorithm_distance(req)

    gap = (algo - optimal) / optimal if optimal > 0 else 0
    print(f"OPTIMAL_DISTANCE={optimal:.4f}")
    print(f"ALGORITHM_DISTANCE={algo:.4f}")
    print(f"OPTIMALITY_GAP={gap:.4f} ({gap*100:.1f}%)")
    assert gap <= 0.10, f"gap {gap:.4f} > 10%"


def test_gap_passenger_2_stops():
    """2 个乘客上下车站。gap <= 5%。"""
    stations = _build_stations(3)
    depot_pt = Point(104.0, 30.0)
    # 上车在 S1，下车在 S2
    pts = [Point(104.02, 30.02), Point(104.04, 30.04)]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]
    req = PlanRequest(
        requestId="gap-pass",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    optimal = _exhaustive_tsp_distance(depot_pt, pts)
    algo = _algorithm_distance(req)

    gap = (algo - optimal) / optimal if optimal > 0 else 0
    print(f"OPTIMAL_DISTANCE={optimal:.4f}")
    print(f"ALGORITHM_DISTANCE={algo:.4f}")
    print(f"OPTIMALITY_GAP={gap:.4f} ({gap*100:.1f}%)")
    assert gap <= 0.05, f"gap {gap:.4f} > 5%"


def test_gap_mixed_3_tasks():
    """1 乘客 + 2 派送，3 个任务节点。gap <= 10%。"""
    stations = _build_stations(4)
    depot_pt = Point(104.0, 30.0)
    pts = [Point(104.02, 30.02), Point(104.04, 30.04), Point(104.06, 30.06)]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    req = PlanRequest(
        requestId="gap-mixed",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    # 乘客引入 2 个节点（上车 + 下车），派送 1 个节点，共 3 个
    optimal = _exhaustive_tsp_distance(depot_pt, pts)
    algo = _algorithm_distance(req)

    gap = (algo - optimal) / optimal if optimal > 0 else 0
    print(f"OPTIMAL_DISTANCE={optimal:.4f}")
    print(f"ALGORITHM_DISTANCE={algo:.4f}")
    print(f"OPTIMALITY_GAP={gap:.4f} ({gap*100:.1f}%)")
    assert gap <= 0.10, f"gap {gap:.4f} > 10%"
