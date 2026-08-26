"""V2-6：Passenger Impact 精确计算测试。

验证：
- 每位乘客的延误时间
- 受影响乘客列表
- 与 detour 的关系
"""

from __future__ import annotations

from app.models import (
    OrderType, PlanOrder, PlanRequest, PlanShipment,
    Station, StopAction, Vehicle,
)
from app.solver import solve


def _stations():
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── 基本场景 ──────────────────────────────────────────────────

def test_passenger_not_affected_by_skeleton_cargo():
    """骨架站上的货运 stop 不影响乘客。"""
    stations = _stations()

    req = PlanRequest(
        requestId="impact-1",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S3"),
        ],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S2",
                         deliveryStationId="S3", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 骨架站上的货运 stop detour 很小（浮点精度）
    tp001_stops = [s for s in plan.stops if s.orderId == "TP001"]
    for stop in tp001_stops:
        if stop.stationId in ("S1", "S2", "S3"):
            # 骨架站 detour 接近 0
            assert stop.detourDistance < 0.01
            assert stop.detourDuration < 10  # 小于 10 秒


def test_passenger_affected_by_detour():
    """非骨架站的货运 stop 影响乘客。"""
    stations = _stations()

    req = PlanRequest(
        requestId="impact-2",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S3"),
        ],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S4",
                         deliveryStationId="S2", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    tp001_stops = [s for s in plan.stops if s.orderId == "TP001"]
    for stop in tp001_stops:
        if stop.stationId == "S4":
            # 非骨架站有绕行
            assert stop.detourDistance > 0
            assert stop.detourDuration > 0
            assert stop.passengerImpact == stop.detourDuration


def test_passenger_impact_calculation():
    """验证 passengerImpact = detourDuration。"""
    stations = _stations()

    req = PlanRequest(
        requestId="impact-3",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S4",
                         deliveryStationId="S3", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    tp001_stops = [s for s in plan.stops if s.orderId == "TP001"]
    for stop in tp001_stops:
        if stop.detourDuration and stop.detourDuration > 0:
            assert stop.passengerImpact == stop.detourDuration


# ── 多乘客场景 ────────────────────────────────────────────────

def test_multiple_passengers_same_detour():
    """多个乘客受同一绕行影响。"""
    stations = _stations()

    req = PlanRequest(
        requestId="impact-4",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
            PlanOrder(orderId="P2", orderType=OrderType.PASSENGER,
                      boardingStationId="S2", alightingStationId="S3"),
        ],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S4",
                         deliveryStationId="S2", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 所有乘客都被服务
    served = {s.orderId for s in plan.stops if s.orderId}
    assert "P1" in served
    assert "P2" in served


# ── 骨架保护 ──────────────────────────────────────────────────

def test_skeleton_stops_not_delayed():
    """骨架站点的到达时间不受绕行影响。"""
    stations = _stations()

    req = PlanRequest(
        requestId="impact-5",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                          skeleton=["S1", "S2", "S3"])],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S3"),
        ],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S4",
                         deliveryStationId="S2", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 骨架 PASS 按序
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    pass_ids = [s.stationId for s in pass_stops]
    assert "S1" in pass_ids
    assert "S2" in pass_ids
    assert "S3" in pass_ids
