"""V2-2：Shipment Pickup/Delivery Pair 约束测试。

验证：
1. 同一 shipment 的 PICKUP 和 DELIVERY 必须同车
2. PICKUP 必须在 DELIVERY 之前
3. 容量约束正确
4. 多个 shipment 不互相干扰
"""

from __future__ import annotations

from app.models import (
    OrderType, PlanOrder, PlanRequest, PlanShipment, Station, StopAction, Vehicle,
)
from app.solver import solve


def _stations():
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── 正常场景 ──────────────────────────────────────────────────

def test_single_shipment_feasible():
    """单个 shipment：PICKUP → DELIVERY，应可行。"""
    stations = _stations()

    req = PlanRequest(
        requestId="single-shipment",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=5),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    pickup_stops = [s for s in plan.stops if s.action == StopAction.PICKUP and s.orderId == "SHP001"]
    delivery_stops = [s for s in plan.stops if s.action == StopAction.DELIVER and s.orderId == "SHP001"]

    assert len(pickup_stops) == 1
    assert len(delivery_stops) == 1
    assert pickup_stops[0].stationId == "S1"
    assert delivery_stops[0].stationId == "S2"


def test_pickup_before_delivery():
    """PICKUP 必须在 DELIVERY 之前。"""
    stations = _stations()

    req = PlanRequest(
        requestId="order-check",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S3", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    pickup_pos = None
    delivery_pos = None
    for i, stop in enumerate(plan.stops):
        if stop.orderId == "SHP001" and stop.action == StopAction.PICKUP:
            pickup_pos = i
        if stop.orderId == "SHP001" and stop.action == StopAction.DELIVER:
            delivery_pos = i

    assert pickup_pos is not None and delivery_pos is not None
    assert pickup_pos < delivery_pos, "PICKUP 必须在 DELIVERY 之前"


def test_same_vehicle_constraint():
    """同一 shipment 的 PICKUP 和 DELIVERY 必须在同一辆车。"""
    stations = _stations()

    req = PlanRequest(
        requestId="same-vehicle",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[
            Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10),
            Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=10),
        ],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S3", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    # 找到 PICKUP 和 DELIVERY 所在的车辆
    for plan in outcome.vehicle_plans:
        pickup = [s for s in plan.stops if s.orderId == "SHP001" and s.action == StopAction.PICKUP]
        delivery = [s for s in plan.stops if s.orderId == "SHP001" and s.action == StopAction.DELIVER]
        # 如果有 PICKUP，必须也有 DELIVERY（同车）
        if pickup:
            assert len(delivery) == 1, f"同车约束：PICKUP 在 Vehicle {plan.vehicleId}，DELIVERY 也必须在"


# ── 容量约束 ──────────────────────────────────────────────────

def test_shipment_respects_cargo_capacity():
    """shipment quantity 必须 ≤ cargoCapacity：整票带不动就不整票接。

    政策变更（2026-10-11，规格 §二.5 订单可舍弃 + 乘客不可舍底线）：
    以前「一票 10 件 > 舱 4」把整批判无解；现在正确出口是这一票如实进
    unassignedOrderIds（订单不可拆，只能整票不接），班次本身不再谎称服务了它。
    实质不变量没变：**绝不出现超舱方案**。
    """
    stations = _stations()

    req = PlanRequest(
        requestId="capacity-check",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=10),  # 10 > 4
        ],
    )

    outcome = solve(req)
    served = {s.orderId for p in (outcome.vehicle_plans or []) for s in p.stops if s.orderId}
    assert "SHP001" not in served, "超舱运单被装上車"
    if outcome.status == "feasible":
        assert "SHP001" in set(outcome.unassigned_order_ids or []), "整票不接却未申报"
    else:
        assert outcome.reason_code == "OVER_CAPACITY"


def test_multiple_shipments_share_capacity():
    """多个 shipment 共享 cargoCapacity：12 件 > 舱 10，必须至少舍掉一整票。"""
    stations = _stations()

    req = PlanRequest(
        requestId="shared-capacity",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=6),
            PlanShipment(shipmentId="SHP002", pickupStationId="S2",
                         deliveryStationId="S3", quantity=6),
        ],
    )

    outcome = solve(req)
    # 两票派送腿合计 12 件 > CargoOut 上限 10 → 不可能两票都接。
    # 政策变更后允许「接一票 + 另一票如实舍弃」，但绝不许两票都算已服务。
    served = {s.orderId for p in (outcome.vehicle_plans or []) for s in p.stops if s.orderId}
    assert not {"SHP001", "SHP002"} <= served, f"12 件塞进 10 件舱：{served}"
    if outcome.status == "feasible":
        unassigned = set(outcome.unassigned_order_ids or [])
        assert served & unassigned == set(), "同一票既被服务又被申报舍弃"
        assert ({"SHP001", "SHP002"} - served) <= unassigned, "带不动的票没进多段联运清单"
    else:
        assert outcome.reason_code == "OVER_CAPACITY"


# ── 多个 shipment ─────────────────────────────────────────────

def test_two_shipments_independent():
    """两个独立 shipment 不互相干扰。"""
    stations = _stations()

    req = PlanRequest(
        requestId="two-shipments",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=3),
            PlanShipment(shipmentId="SHP002", pickupStationId="S3",
                         deliveryStationId="S4", quantity=4),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    served = {s.orderId for s in plan.stops if s.orderId}
    assert "SHP001" in served
    assert "SHP002" in served

    # 每个 shipment 都有 PICKUP 和 DELIVERY
    for shp_id in ["SHP001", "SHP002"]:
        pickups = [s for s in plan.stops if s.orderId == shp_id and s.action == StopAction.PICKUP]
        deliveries = [s for s in plan.stops if s.orderId == shp_id and s.action == StopAction.DELIVER]
        assert len(pickups) == 1
        assert len(deliveries) == 1


def test_shipment_with_passenger_and_order():
    """shipment + PlanOrder 可以共存。"""
    stations = _stations()

    req = PlanRequest(
        requestId="mixed",
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
            PlanShipment(shipmentId="SHP001", pickupStationId="S2",
                         deliveryStationId="S4", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    served = {s.orderId for s in plan.stops if s.orderId}
    assert "P1" in served
    assert "SHP001" in served


def test_shipment_with_skeleton():
    """shipment + 骨架约束可以共存。"""
    stations = _stations()

    req = PlanRequest(
        requestId="shipment-skeleton",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[
            Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                    skeleton=["S0", "S1", "S2", "S3"]),
        ],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
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
