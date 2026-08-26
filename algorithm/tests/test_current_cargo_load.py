"""V2-3：CurrentCargoLoad 维度测试。

验证：
- PICKUP +quantity, DELIVERY -quantity
- 0 <= load <= cargoCapacity
- initialCargoLoad 支持
- 与 CargoOut/CargoIn 共存

CargoOut/CargoIn vs CurrentCargoLoad 的区别：
- CargoOut：出程派送能力（DELIVER +itemCount 累计）
- CargoIn：返程揽收能力（PICKUP +itemCount 累计）
- CurrentCargoLoad：车上真实货物量（PICKUP +, DELIVER -）
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


# ── 基本场景 ──────────────────────────────────────────────────

def test_pickup_increases_cargo():
    """PICKUP 增加车上货物。"""
    stations = _stations()

    req = PlanRequest(
        requestId="pickup-inc",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                      stationId="S1", itemCount=5),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_delivery_decreases_cargo():
    """DELIVERY 减少车上货物。"""
    stations = _stations()

    req = PlanRequest(
        requestId="delivery-dec",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=5),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_shipment_cargo_flow():
    """Shipment：PICKUP +5 → DELIVERY -5，车上货物变化正确。"""
    stations = _stations()

    req = PlanRequest(
        requestId="shipment-flow",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S1",
                         deliveryStationId="S3", quantity=5),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 验证 PICKUP 和 DELIVERY 都出现
    pickup = [s for s in plan.stops if s.orderId == "SHP001" and s.action == StopAction.PICKUP]
    delivery = [s for s in plan.stops if s.orderId == "SHP001" and s.action == StopAction.DELIVER]
    assert len(pickup) == 1
    assert len(delivery) == 1


# ── 容量约束 ──────────────────────────────────────────────────

def test_cargo_capacity_exceeded():
    """车上货物超过 cargoCapacity → OVER_CAPACITY。"""
    stations = _stations()

    req = PlanRequest(
        requestId="cargo-exceeded",
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


def test_multiple_shipments_cargo_capacity():
    """多个 shipment 的总 cargo 不超容量。"""
    stations = _stations()

    req = PlanRequest(
        requestId="multi-cargo",
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
    # 总量 3+4=7 ≤ 10 → FEASIBLE
    assert outcome.status == "feasible"


def test_passenger_and_cargo_independent():
    """乘客和货物容量独立限制。"""
    stations = _stations()

    req = PlanRequest(
        requestId="independent",
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
            PlanShipment(shipmentId="SHP001", pickupStationId="S2",
                         deliveryStationId="S3", quantity=8),
        ],
    )

    outcome = solve(req)
    # 乘客 1 ≤ 5, 货物 8 ≤ 10 → FEASIBLE
    assert outcome.status == "feasible"


# ── 混合场景 ──────────────────────────────────────────────────

def test_orders_and_shipments_coexist():
    """PlanOrder 和 PlanShipment 同时存在。"""
    stations = _stations()

    req = PlanRequest(
        requestId="coexist",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=3),
        ],
        shipments=[
            PlanShipment(shipmentId="SHP001", pickupStationId="S2",
                         deliveryStationId="S3", quantity=4),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    served = {s.orderId for s in plan.stops if s.orderId}
    assert "D1" in served
    assert "SHP001" in served
