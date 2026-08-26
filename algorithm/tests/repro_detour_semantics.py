"""P1-4：验证 detourDistance 语义。

规则：
  detourDistance = (prev→cargo + cargo→next) - (prev→next)
  不是 segmentDistance（入弧距离）。
"""

from __future__ import annotations

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_detour_is_real_detour_not_segment():
    """非骨架站的 detourDistance 应为真实绕行距离，不是入弧距离。"""
    # 构造一个可以计算绕行的场景
    # S0(depot) → S1 → S2 → S3 是骨架
    # S4 是非骨架站，插入到 S1 和 S2 之间
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),  # 非骨架站
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="detour-semantics",
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
    k1_stop = next(s for s in plan.stops if s.orderId == "K1")

    # detourDistance 应 > 0（因为 S4 不在骨架上）
    assert k1_stop.detourDistance is not None
    assert k1_stop.detourDistance > 0, "非骨架站 detour 应 > 0"

    # detourDuration 应 > 0
    assert k1_stop.detourDuration is not None
    assert k1_stop.detourDuration > 0, "非骨架站 detour duration 应 > 0"


def test_detour_zero_for_skeleton_stop():
    """骨架站上的货运 stop detourDistance=0。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(4)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S2", itemCount=1),  # S2 在骨架上
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="detour-skeleton",
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
    k1_stop = next(s for s in plan.stops if s.orderId == "K1")
    assert k1_stop.detourDistance == 0.0
    assert k1_stop.detourDuration == 0.0
