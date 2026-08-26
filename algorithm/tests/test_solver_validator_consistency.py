"""V2-4.1：Solver ↔ Validator 一致性测试。

规则：
  Solver 判定 FEASIBLE → Validator 必须 PASS
  Validator FAIL → Solver 不得返回 FEASIBLE
"""

from __future__ import annotations

from app.models import (
    CargoSource, OrderType, PlanOrder, PlanRequest, PlanShipment,
    Station, StopAction, Vehicle,
)
from app.solver import solve
from app.validators import validate_vehicle_plan


def _stations():
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


def _validate_all_plans(outcome, request):
    """对 Solver 输出的每个 VehiclePlan 运行 Validator。"""
    if outcome.status != "feasible":
        return True, None  # Solver 不可行，不需要验证

    vehicles_by_id = {v.vehicleId: v for v in request.vehicles}
    orders_by_id = {o.orderId: o for o in request.orders}

    for plan in outcome.vehicle_plans:
        vehicle = vehicles_by_id.get(plan.vehicleId)
        if not vehicle:
            return False, "VEHICLE_NOT_FOUND"

        valid, reason = validate_vehicle_plan(plan, vehicle, orders_by_id)
        if not valid:
            return False, reason

    return True, None


# ── 正常场景 ──────────────────────────────────────────────────

def test_consistency_normal_orders():
    """正常 PlanOrder：Solver FEASIBLE → Validator PASS。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-1",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S3", itemCount=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    valid, reason = _validate_all_plans(outcome, req)
    assert valid, f"Validator 应 PASS，实际 {reason}"


def test_consistency_shipment():
    """Shipment：Solver FEASIBLE → Validator PASS。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-2",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=20),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    valid, reason = _validate_all_plans(outcome, req)
    assert valid, f"Validator 应 PASS，实际 {reason}"


def test_consistency_preloaded_delivery():
    """PRELOADED DELIVERY：Solver FEASIBLE → Validator PASS。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-3",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100,
                          initialCargoLoad=50)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=30,
                      cargoSource=CargoSource.PRELOADED),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    valid, reason = _validate_all_plans(outcome, req)
    assert valid, f"Validator 应 PASS，实际 {reason}"


def test_consistency_mixed_sources():
    """混合源：Solver FEASIBLE → Validator PASS。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-4",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100,
                          initialCargoLoad=50)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=20,
                      cargoSource=CargoSource.PRELOADED),
        ],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S2",
                         deliveryStationId="S3", quantity=30),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    valid, reason = _validate_all_plans(outcome, req)
    assert valid, f"Validator 应 PASS，实际 {reason}"


# ── 容量边界 ──────────────────────────────────────────────────

def test_consistency_capacity_boundary():
    """容量边界：Solver FEASIBLE → Validator PASS。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-5",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=10),  # 正好等于容量
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    valid, reason = _validate_all_plans(outcome, req)
    assert valid, f"Validator 应 PASS，实际 {reason}"


# ── Solver 不可行时 Validator 不需要验证 ───────────────────────

def test_consistency_infeasible_skips_validation():
    """Solver INFEASIBLE → Validator 不需要运行。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-6",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=10),  # 10 > 4
        ],
    )

    outcome = solve(req)
    assert outcome.status == "infeasible"

    # Validator 不需要运行
    valid, reason = _validate_all_plans(outcome, req)
    assert valid


# ── 向后兼容 ──────────────────────────────────────────────────

def test_consistency_backward_compatible():
    """cargoSource=None：Solver FEASIBLE → Validator PASS。"""
    stations = _stations()

    req = PlanRequest(
        requestId="consistency-7",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=3),  # cargoSource=None
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    valid, reason = _validate_all_plans(outcome, req)
    assert valid, f"Validator 应 PASS，实际 {reason}"
