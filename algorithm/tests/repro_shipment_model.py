"""V2-1：Shipment 数据模型测试。

验证 PlanShipment 模型的正确性：
- 字段校验
- 与 PlanOrder 兼容
- Solver 展开逻辑
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.models import (
    OrderType, PlanOrder, PlanRequest, PlanShipment, Station, StopAction, Vehicle,
)
from app.solver import solve


# ── PlanShipment 模型测试 ─────────────────────────────────────

def test_plan_shipment_required_fields():
    """PlanShipment 必须有 shipmentId、pickupStationId、deliveryStationId、quantity。"""
    # 正常
    s = PlanShipment(
        shipmentId="SHP001",
        pickupStationId="A",
        deliveryStationId="B",
        quantity=5,
    )
    assert s.shipmentId == "SHP001"
    assert s.quantity == 5

    # 缺少 shipmentId
    with pytest.raises(ValidationError):
        PlanShipment(pickupStationId="A", deliveryStationId="B", quantity=5)

    # quantity < 1
    with pytest.raises(ValidationError):
        PlanShipment(shipmentId="SHP001", pickupStationId="A", deliveryStationId="B", quantity=0)


def test_plan_shipment_optional_fields():
    """weightKg 和 volumeM3 是可选的。"""
    s = PlanShipment(
        shipmentId="SHP001",
        pickupStationId="A",
        deliveryStationId="B",
        quantity=5,
        weightKg=10.5,
        volumeM3=0.3,
    )
    assert s.weightKg == 10.5
    assert s.volumeM3 == 0.3

    # 不传也行
    s2 = PlanShipment(
        shipmentId="SHP002",
        pickupStationId="A",
        deliveryStationId="B",
        quantity=1,
    )
    assert s2.weightKg is None
    assert s2.volumeM3 is None


# ── PlanRequest 兼容性 ────────────────────────────────────────

def test_plan_request_with_shipments():
    """PlanRequest 可以同时有 orders 和 shipments。"""
    req = PlanRequest(
        requestId="test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=Station(stationId="S0", longitude=104.0, latitude=30.0),
        stations=[],
        vehicles=[Vehicle(vehicleId=1)],
        orders=[],
        shipments=[
            PlanShipment(
                shipmentId="SHP001",
                pickupStationId="S1",
                deliveryStationId="S2",
                quantity=3,
            ),
        ],
    )
    assert len(req.shipments) == 1
    assert req.shipments[0].shipmentId == "SHP001"


def test_plan_request_shipments_default_empty():
    """PlanRequest.shipments 默认为空列表。"""
    req = PlanRequest(
        requestId="test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=Station(stationId="S0", longitude=104.0, latitude=30.0),
        stations=[],
        vehicles=[Vehicle(vehicleId=1)],
        orders=[],
    )
    assert req.shipments == []


# ── Solver 展开测试 ───────────────────────────────────────────

def test_shipment_expanded_to_pickup_delivery():
    """Solver 应将 PlanShipment 展开为 PICKUP + DELIVERY 节点。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    req = PlanRequest(
        requestId="shipment-expand",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(
                shipmentId="SHP001",
                pickupStationId="S1",
                deliveryStationId="S2",
                quantity=5,
            ),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 验证 PICKUP 和 DELIVERY 都出现
    pickup_stops = [s for s in plan.stops if s.action == StopAction.PICKUP]
    delivery_stops = [s for s in plan.stops if s.action == StopAction.DELIVER]

    assert len(pickup_stops) == 1
    assert len(delivery_stops) == 1
    assert pickup_stops[0].stationId == "S1"
    assert delivery_stops[0].stationId == "S2"


def test_shipment_orders_coexist():
    """PlanShipment 和 PlanOrder 可以共存。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
        Station(stationId="S3", longitude=104.030, latitude=30.030),
    ]

    req = PlanRequest(
        requestId="coexist",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
        ],
        shipments=[
            PlanShipment(
                shipmentId="SHP001",
                pickupStationId="S2",
                deliveryStationId="S3",
                quantity=3,
            ),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    served_orders = {s.orderId for s in plan.stops if s.orderId}
    assert "P1" in served_orders
    assert "SHP001" in served_orders


def test_multiple_shipments():
    """多个 PlanShipment 同时求解。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
        Station(stationId="S3", longitude=104.030, latitude=30.030),
    ]

    req = PlanRequest(
        requestId="multi-shipment",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=3),
            PlanShipment(shipmentId="SHP002", pickupStationId="S2",
                         deliveryStationId="S3", quantity=4),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    served = {s.orderId for s in plan.stops if s.orderId}
    assert "SHP001" in served
    assert "SHP002" in served


def test_shipment_capacity_exceeded():
    """shipment 总量超容量 → OVER_CAPACITY。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    req = PlanRequest(
        requestId="over-capacity",
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
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "OVER_CAPACITY"
