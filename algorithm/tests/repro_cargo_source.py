"""V2-4：CargoSource 区分最小复现。

验证 PRELOADED_DELIVERY 和 SHIPMENT_DELIVERY 的正确行为。

PRELOADED_DELIVERY：
- 货物由场站预装，消耗 initialCargoLoad
- 不需要对应 PICKUP
- initialCargoLoad 必须 >= delivery quantity

SHIPMENT_DELIVERY：
- 任务段内 PICKUP→DELIVERY 配对
- 必须同一 shipmentId、同一 vehicle、PICKUP 在 DELIVERY 前
"""

from __future__ import annotations

from app.models import (
    CargoSource, OrderType, PlanOrder, PlanRequest, PlanShipment, Station, StopAction, Vehicle,
)
from app.solver import solve


def _stations():
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── PRELOADED DELIVERY 测试 ───────────────────────────────────

def test_case1_preloaded_delivery_feasible():
    """initialCargo=100, PRELOADED DELIVERY 20 → finalCargo=80 → FEASIBLE"""
    stations = _stations()

    req = PlanRequest(
        requestId="preloaded-1",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100,
                          initialCargoLoad=100)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=20,
                      cargoSource=CargoSource.PRELOADED),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_case2_preloaded_delivery_full():
    """initialCargo=100, PRELOADED DELIVERY 100 → finalCargo=0 → FEASIBLE"""
    stations = _stations()

    req = PlanRequest(
        requestId="preloaded-2",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100,
                          initialCargoLoad=100)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=100,
                      cargoSource=CargoSource.PRELOADED),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_case3_preloaded_delivery_insufficient():
    """initialCargo=0, PRELOADED DELIVERY 20 → INFEASIBLE"""
    stations = _stations()

    req = PlanRequest(
        requestId="preloaded-3",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100,
                          initialCargoLoad=0)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=20,
                      cargoSource=CargoSource.PRELOADED),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "infeasible"


# ── SHIPMENT DELIVERY 测试 ────────────────────────────────────

def test_case4_shipment_pickup_delivery():
    """SHIPMENT: PICKUP 20 → DELIVERY 20 → FEASIBLE"""
    stations = _stations()

    req = PlanRequest(
        requestId="shipment-1",
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


def test_case5_shipment_reverse_order():
    """SHIPMENT: DELIVERY before PICKUP → INFEASIBLE（CargoLoad 维度）"""
    stations = _stations()

    # 构造一个会强制 DELIVERY 在 PICKUP 前的场景
    # 通过骨架约束强制顺序
    req = PlanRequest(
        requestId="shipment-2",
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

    # 验证 PICKUP 在 DELIVERY 之前
    plan = outcome.vehicle_plans[0]
    pickup_pos = None
    delivery_pos = None
    for i, stop in enumerate(plan.stops):
        if stop.orderId == "TP001" and stop.action == StopAction.PICKUP:
            pickup_pos = i
        if stop.orderId == "TP001" and stop.action == StopAction.DELIVER:
            delivery_pos = i

    assert pickup_pos is not None and delivery_pos is not None
    assert pickup_pos < delivery_pos


def test_case6_shipment_different_vehicles():
    """SHIPMENT: PICKUP 和 DELIVERY 不同车辆 → INFEASIBLE"""
    stations = _stations()

    req = PlanRequest(
        requestId="shipment-3",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[
            Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100),
            Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=100),
        ],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S1",
                         deliveryStationId="S3", quantity=20),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    # 验证 PICKUP 和 DELIVERY 在同一辆车
    for plan in outcome.vehicle_plans:
        pickup = [s for s in plan.stops if s.orderId == "TP001" and s.action == StopAction.PICKUP]
        delivery = [s for s in plan.stops if s.orderId == "TP001" and s.action == StopAction.DELIVER]
        if pickup:
            assert len(delivery) == 1, "同车约束：PICKUP 和 DELIVERY 必须在同一辆车"


def test_case7_different_shipments_not_mixed():
    """不同 shipment 不得混淆。"""
    stations = _stations()

    req = PlanRequest(
        requestId="shipment-4",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S1",
                         deliveryStationId="S2", quantity=20),
            PlanShipment(shipmentId="TP002", pickupStationId="S2",
                         deliveryStationId="S3", quantity=30),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 每个 shipment 的 PICKUP 和 DELIVERY 配对正确
    for shp_id in ["TP001", "TP002"]:
        pickups = [s for s in plan.stops if s.orderId == shp_id and s.action == StopAction.PICKUP]
        deliveries = [s for s in plan.stops if s.orderId == shp_id and s.action == StopAction.DELIVER]
        assert len(pickups) == 1
        assert len(deliveries) == 1


# ── 混合场景 ──────────────────────────────────────────────────

def test_case8_preloaded_and_shipment_coexist():
    """PRELOADED DELIVERY 和 SHIPMENT 可以共存。"""
    stations = _stations()

    req = PlanRequest(
        requestId="mixed-1",
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


def test_case9_backward_compatible():
    """向后兼容：cargoSource=None 的 DELIVERY 按 PRELOADED 处理。"""
    stations = _stations()

    req = PlanRequest(
        requestId="compat-1",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=100,
                          initialCargoLoad=50)],
        orders=[
            # cargoSource 缺省，按 PRELOADED 处理
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S1", itemCount=20),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"
