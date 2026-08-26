"""Phase 8：有限绕行复现实验。

目标：验证货运 stop 的绕行距离/时间在阈值内。

当前实现：
  detourDistance = segment_km（非骨架站的入弧距离）
  detourDuration = segment_seconds

改进方向：
  detourDistance = route_with_stop - direct_route（真实绕行）

第一阶段：post-solve 验证绕行阈值。
"""

from __future__ import annotations

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_cargo_on_skeleton_has_zero_detour():
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
        requestId="detour-test-1",
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
    assert k1_stop.detourDistance == 0.0, "骨架站上的货运 stop detour 应为 0"
    assert k1_stop.detourDuration == 0.0


def test_cargo_off_skeleton_has_detour():
    """非骨架站的货运 stop detourDistance > 0。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(6)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),  # S4 不在骨架上
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="detour-test-2",
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
    assert k1_stop.detourDistance is not None and k1_stop.detourDistance > 0, (
        "非骨架站 detour 应 > 0"
    )
    assert k1_stop.serviceMode == "NEAREST_STATION"


def test_detour_within_threshold():
    """绕行距离在阈值内 → accepted=True。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S3", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2"]),
    ]

    req = PlanRequest(
        requestId="detour-threshold",
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
    assert k1_stop.accepted is True


def test_cargo_stop_fields_complete():
    """货运 stop 必须携带完整的算法解释字段。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]

    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=2),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2"]),
    ]

    req = PlanRequest(
        requestId="fields-test",
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

    for order_id in ["D1", "K1"]:
        stop = next(s for s in plan.stops if s.orderId == order_id)
        assert stop.accepted is True
        assert stop.serviceMode == "NEAREST_STATION"
        assert stop.servicePoint == stop.stationId
        assert stop.detourDistance is not None
        assert stop.detourDuration is not None
        # V2-5: passengerImpact = detourDuration（route-level）
        if stop.detourDuration and stop.detourDuration > 0:
            assert stop.passengerImpact == stop.detourDuration
        assert stop.reasonCode is None


def test_multiple_cargo_stops_independent_detour():
    """多个货运 stop 各自独立计算绕行。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(7)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
        PlanOrder(orderId="K2", orderType=OrderType.PICKUP,
                  stationId="S5", itemCount=1),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S6", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="multi-cargo",
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
    cargo_stops = [s for s in plan.stops if s.orderId]
    assert len(cargo_stops) == 3

    # 所有货运 stop 都有完整字段
    for stop in cargo_stops:
        assert stop.accepted is True
        assert stop.detourDistance is not None
        assert stop.detourDuration is not None


def test_skeleton_order_preserved_with_cargo():
    """插入货运 stop 后骨架顺序不变。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(6)
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
        PlanOrder(orderId="K2", orderType=OrderType.PICKUP,
                  stationId="S5", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="skeleton-order",
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
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    pass_ids = [s.stationId for s in pass_stops]
    assert pass_ids == ["S0", "S1", "S2", "S3"], f"骨架顺序应保持: {pass_ids}"
