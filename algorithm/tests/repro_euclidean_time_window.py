"""P1-2：验证欧氏路径也有时间估算，时间窗口验证正常工作。

场景：batchEnd 很短（5分钟），欧氏估算需要更长时间 → INFEASIBLE/TIME_WINDOW_EXCEEDED。
"""

from __future__ import annotations

from app.models import OrderType, PlanOrder, PlanRequest, Station, Vehicle
from app.solver import solve


def test_euclidean_time_window_exceeded():
    """batchEnd=08:05（5分钟），站点距离远，欧氏估算超时。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.100, latitude=30.100),  # ~15km
        Station(stationId="S2", longitude=104.200, latitude=30.200),  # ~30km
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]

    req = PlanRequest(
        requestId="euclidean-time",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T08:05:00+08:00",  # 只有 5 分钟
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req)
    # 距离远 + 时间短 → 应超时
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "TIME_WINDOW_EXCEEDED"


def test_euclidean_time_window_sufficient():
    """batchEnd=23:00（15小时），时间充足。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),  # ~1.5km
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]

    req = PlanRequest(
        requestId="euclidean-ok",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_euclidean_duration_not_none():
    """欧氏路径的 segmentDuration 不应为 None。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S0", alightingStationId="S1"),
    ]

    req = PlanRequest(
        requestId="duration-check",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        for stop in plan.stops[1:]:
            assert stop.segmentDuration is not None
            assert stop.segmentDuration >= 0
