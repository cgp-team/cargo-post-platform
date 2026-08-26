"""Phase 9：真实路网成本复现实验。

目标：验证 DistanceMatrix 注入时 distanceKm/durationSeconds 的正确使用。

规则：
  - 传入 DistanceMatrix 时优先使用 distanceKm 和 durationSeconds
  - 不混用 degree 和 km
  - solver 内部 scaled integer，输出 km
  - 不可达点对不能变成 0km，必须明确不可达
"""

from __future__ import annotations

import pytest

from app.distance import EuclideanDistanceProvider
from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId="S1", longitude=104.010, latitude=30.010),
    Station(stationId="S2", longitude=104.020, latitude=30.020),
    Station(stationId="S3", longitude=104.030, latitude=30.030),
]
POINTS = [DEPOT, *STATIONS]


def _make_matrix(km: float = 10.0, seconds: float = 600.0):
    """创建固定值的距离矩阵。"""
    matrix: dict[tuple[str, str], tuple[float, float | None]] = {}
    for a in POINTS:
        for b in POINTS:
            if a.stationId == b.stationId:
                matrix[(a.stationId, b.stationId)] = (0.0, 0.0)
            else:
                matrix[(a.stationId, b.stationId)] = (km, seconds)
    return matrix


def _make_request():
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    return PlanRequest(
        requestId="road-test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )


# ── Case 1: 矩阵注入使用 km ──────────────────────────────────

def test_case1_matrix_uses_km():
    """注入矩阵后，segmentDistance 应为 km 值。"""
    matrix = _make_matrix(km=10.0, seconds=600.0)
    outcome = solve(_make_request(), matrix)
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        for stop in plan.stops[1:]:  # 跳过 DEPART
            assert stop.segmentDistance == 10.0, (
                f"segmentDistance 应为 10.0 km，实际 {stop.segmentDistance}"
            )


# ── Case 2: 矩阵注入使用 seconds ─────────────────────────────

def test_case2_matrix_uses_seconds():
    """注入矩阵后，segmentDuration 应为 seconds 值。"""
    matrix = _make_matrix(km=10.0, seconds=600.0)
    outcome = solve(_make_request(), matrix)
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        for stop in plan.stops[1:]:
            if stop.segmentDuration is not None:
                assert stop.segmentDuration == 600.0, (
                    f"segmentDuration 应为 600.0s，实际 {stop.segmentDuration}"
                )


# ── Case 3: 欧氏路径无 segmentDuration ────────────────────────

def test_case3_euclidean_has_estimated_duration():
    """欧氏路径（无矩阵）：segmentDuration 为 Haversine 均速估算值（非 None）。"""
    outcome = solve(_make_request())
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        for stop in plan.stops[1:]:  # 跳过 DEPART
            assert stop.segmentDuration is not None
            assert stop.segmentDuration >= 0


# ── Case 4: 欧氏 provider 与内置一致 ──────────────────────────

def test_case4_euclidean_provider_matches_builtin():
    """EuclideanDistanceProvider 注入与内置 hypot 路径结果一致。"""
    request = _make_request()
    matrix = EuclideanDistanceProvider().get_matrix(POINTS)
    injected = solve(request, matrix)
    builtin = solve(request)
    assert injected.status == builtin.status == "feasible"
    assert injected.total_distance == builtin.total_distance


# ── Case 5: 不同距离值 ────────────────────────────────────────

def test_case5_different_distances():
    """不同距离值应正确反映在输出中。"""
    matrix = _make_matrix(km=5.5, seconds=330.0)
    outcome = solve(_make_request(), matrix)
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        for stop in plan.stops[1:]:
            assert stop.segmentDistance == 5.5


# ── Case 6: 同站距离为 0 ──────────────────────────────────────

def test_case6_same_station_zero_distance():
    """同站距离应为 0。"""
    matrix = _make_matrix(km=10.0, seconds=600.0)
    # S1 到 S1 的距离应为 0
    assert matrix[("S1", "S1")] == (0.0, 0.0)


# ── Case 7: 总距离计算 ────────────────────────────────────────

def test_case7_total_distance_correct():
    """总距离 = 所有 segmentDistance 之和。"""
    matrix = _make_matrix(km=10.0, seconds=600.0)
    outcome = solve(_make_request(), matrix)
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        segment_sum = round(sum(stop.segmentDistance for stop in plan.stops), 3)
        assert segment_sum == plan.totalDistance


# ── Case 8: 不可达点对处理 ────────────────────────────────────

def test_case8_unavailable_pair():
    """不可达点对（matrix 缺少 key）应返回 infeasible/DISTANCE_MATRIX_INCOMPLETE。
    修复：添加矩阵完整性预检，防止 pywrapcp 崩溃。
    """
    # 创建一个缺少某些 key 的矩阵
    matrix: dict[tuple[str, str], tuple[float, float | None]] = {}
    for a in POINTS:
        for b in POINTS:
            if a.stationId == b.stationId:
                matrix[(a.stationId, b.stationId)] = (0.0, 0.0)
            elif a.stationId == "S0" and b.stationId == "S1":
                # 不可达：不添加此 key
                pass
            else:
                matrix[(a.stationId, b.stationId)] = (10.0, 600.0)

    outcome = solve(_make_request(), matrix)
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "DISTANCE_MATRIX_INCOMPLETE"


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
