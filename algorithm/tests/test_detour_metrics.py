"""P1-B：验证 detourDistance + detourDuration 统一计算。

规则：
  detourDistance = (prev→cargo + cargo→next) - (prev→next)  >= 0
  detourDuration = (prev→cargo_dur + cargo→next_dur) - (prev→next_dur)  >= 0

覆盖：
  - Euclidean 模式（无 matrix）
  - AMAP mock matrix
  - 骨架站（detour=0）
  - 不可达（matrix 缺 key → DISTANCE_MATRIX_INCOMPLETE）
"""

from __future__ import annotations

from app.distance import EUCLIDEAN_AVG_SPEED_KMH, haversine_km
from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def _make_stations():
    """S0(depot), S1, S2, S3, S4。S4 不在骨架上。"""
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── Euclidean 模式 ────────────────────────────────────────────

def test_euclidean_detour_distance_positive():
    """Euclidean 模式：非骨架站 detourDistance > 0。"""
    stations = _make_stations()

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="euclidean-detour",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDistance is not None and k1.detourDistance > 0
    assert k1.detourDuration is not None and k1.detourDuration > 0


def test_euclidean_detour_duration_consistent():
    """Euclidean 模式：detourDuration 由各段 Haversine 独立计算，≥ 0。"""
    stations = _make_stations()

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="euclidean-consistency",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDuration is not None and k1.detourDuration >= 0


# ── AMAP mock matrix ──────────────────────────────────────────

def test_amap_detour_with_matrix():
    """AMAP matrix 模式：detour 使用 matrix 中的 km 和 seconds。"""
    stations = _make_stations()

    # 构造 matrix：所有点对 10km / 600s
    matrix: dict[tuple[str, str], tuple[float, float | None]] = {}
    station_ids = [s.stationId for s in stations]
    for a in station_ids:
        for b in station_ids:
            if a == b:
                matrix[(a, b)] = (0.0, 0.0)
            else:
                matrix[(a, b)] = (10.0, 600.0)

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="amap-detour",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req, matrix)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDistance is not None and k1.detourDistance > 0
    assert k1.detourDuration is not None and k1.detourDuration > 0


# ── 骨架站 detour=0 ──────────────────────────────────────────

def test_skeleton_stop_detour_zero():
    """骨架站上的货运 stop detour=0。"""
    stations = _make_stations()

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S2", itemCount=1),  # S2 在骨架上
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="skeleton-detour-zero",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDistance == 0.0
    assert k1.detourDuration == 0.0


# ── 不可达 matrix ─────────────────────────────────────────────

def test_incomplete_matrix_returns_error():
    """matrix 缺少 key → DISTANCE_MATRIX_INCOMPLETE。"""
    stations = _make_stations()

    matrix: dict[tuple[str, str], tuple[float, float | None]] = {}
    station_ids = [s.stationId for s in stations]
    for a in station_ids:
        for b in station_ids:
            if a == b:
                matrix[(a, b)] = (0.0, 0.0)
            elif a == "S0" and b == "S1":
                pass  # 缺失
            else:
                matrix[(a, b)] = (10.0, 600.0)

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]

    req = PlanRequest(
        requestId="incomplete-matrix",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req, matrix)
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "DISTANCE_MATRIX_INCOMPLETE"
