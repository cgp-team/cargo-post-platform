"""P1-5：验证 passengerImpact 语义。

规则：
  - 骨架站上的货运 stop: passengerImpact=None（无绕行）
  - 非骨架站的货运 stop: passengerImpact=None（未真实计算前不伪造 0）
"""

from __future__ import annotations

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_passenger_impact_empty_vehicle_none():
    """passenger-level：空车（无乘客订单、无初始载荷）绕行不影响乘客 → passengerImpact=None。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S2", itemCount=1),  # 骨架站
        PlanOrder(orderId="K2", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),  # 非骨架站
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="passenger-impact",
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
    for stop in plan.stops:
        if stop.orderId and stop.action in (StopAction.PICKUP, StopAction.DELIVER):
            # 空车：绕行不影响乘客，passengerImpact=None（passenger-level）
            assert stop.passengerImpact is None
