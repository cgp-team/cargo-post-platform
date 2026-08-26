"""V2-4.1：CargoSource Validator 测试。

验证 validate_cargo_load 按 CargoSource 正确处理：
- PRELOADED DELIVERY：消耗 initialCargoLoad
- SHIPMENT PICKUP/DELIVERY：+/- quantity
- standalone：旧逻辑
- cargoSource=None：向后兼容
"""

from __future__ import annotations

from app.models import CargoSource, OrderType, PlanOrder, RouteStop, StopAction, VehiclePlan
from app.validators import validate_cargo_load


def _make_plan(stops: list[tuple[str, StopAction, str | None]]) -> VehiclePlan:
    """构建测试用 VehiclePlan。"""
    return VehiclePlan(
        vehicleId=1,
        stops=[
            RouteStop(stationId="S0", action=StopAction.DEPART, orderId=None),
            *[
                RouteStop(stationId=sid, action=action, orderId=oid)
                for sid, action, oid in stops
            ],
            RouteStop(stationId="S0", action=StopAction.RETURN, orderId=None),
        ],
        totalDistance=0.0,
    )


def _make_orders(orders: list[tuple[str, OrderType, int, CargoSource | None]]) -> dict[str, PlanOrder]:
    """构建测试用 orders_by_id。"""
    return {
        oid: PlanOrder(orderId=oid, orderType=otype, stationId="S1", itemCount=qty, cargoSource=source)
        for oid, otype, qty, source in orders
    }


# ── CASE 1: PRELOADED DELIVERY 正常 ──────────────────────────

def test_case1_preloaded_delivery_pass():
    """initialCargo=100, PRELOADED DELIVERY 20 → PASS"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "D1"),
    ])
    orders = _make_orders([
        ("D1", OrderType.DELIVERY, 20, CargoSource.PRELOADED),
    ])

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100, initial_cargo_load=100)
    assert valid, f"应 PASS，实际 {reason}"


# ── CASE 2: PRELOADED DELIVERY 超量 ──────────────────────────

def test_case2_preloaded_delivery_insufficient():
    """initialCargo=20, PRELOADED DELIVERY 30 → PRELOAD_INSUFFICIENT"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "D1"),
    ])
    orders = _make_orders([
        ("D1", OrderType.DELIVERY, 30, CargoSource.PRELOADED),
    ])

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100, initial_cargo_load=20)
    assert not valid
    assert reason == "PRELOAD_INSUFFICIENT"


# ── CASE 3: SHIPMENT PICKUP + DELIVERY ────────────────────────

def test_case3_shipment_pickup_delivery():
    """SHIPMENT: PICKUP 20 → DELIVERY 20 → PASS"""
    plan = _make_plan([
        ("S1", StopAction.PICKUP, "TP001"),
        ("S2", StopAction.DELIVER, "TP001"),
    ])
    orders = _make_orders([
        ("TP001", OrderType.PICKUP, 20, CargoSource.SHIPMENT),
    ])
    # Shipment 的 DELIVERY 也用同一个 orderId
    orders["TP001-D"] = PlanOrder(
        orderId="TP001", orderType=OrderType.DELIVERY,
        stationId="S2", itemCount=20, cargoSource=CargoSource.SHIPMENT,
    )

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100)
    assert valid, f"应 PASS，实际 {reason}"


# ── CASE 4: SHIPMENT DELIVERY before PICKUP ───────────────────

def test_case4_shipment_delivery_before_pickup():
    """SHIPMENT: DELIVERY 20 → PICKUP 20 → CARGO_LOAD_NEGATIVE"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "TP001"),
        ("S2", StopAction.PICKUP, "TP001"),
    ])
    orders = _make_orders([
        ("TP001", OrderType.DELIVERY, 20, CargoSource.SHIPMENT),
    ])
    orders["TP001-P"] = PlanOrder(
        orderId="TP001", orderType=OrderType.PICKUP,
        stationId="S2", itemCount=20, cargoSource=CargoSource.SHIPMENT,
    )

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100)
    assert not valid
    assert reason == "CARGO_LOAD_NEGATIVE"


# ── CASE 5: standalone DELIVERY 旧逻辑 ────────────────────────

def test_case5_standalone_delivery_legacy():
    """standalone DELIVERY (cargoSource=None) → 旧逻辑"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "D1"),
    ])
    orders = _make_orders([
        ("D1", OrderType.DELIVERY, 20, None),  # cargoSource=None
    ])

    # 旧逻辑：standalone DELIVERY 不检查负值（CargoOut 保证）
    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100)
    assert valid, f"旧逻辑应 PASS，实际 {reason}"


# ── CASE 6: cargoSource=None 向后兼容 ─────────────────────────

def test_case6_backward_compatible():
    """cargoSource=None 完全向后兼容。"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "D1"),
        ("S2", StopAction.PICKUP, "K1"),
    ])
    orders = _make_orders([
        ("D1", OrderType.DELIVERY, 20, None),
        ("K1", OrderType.PICKUP, 10, None),
    ])

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100)
    assert valid, f"向后兼容应 PASS，实际 {reason}"


# ── CASE 7: SHIPMENT 超容量 ───────────────────────────────────

def test_case7_shipment_over_capacity():
    """SHIPMENT PICKUP 60 + initialCargo=50 → 110 > 100 → EXCEEDED"""
    plan = _make_plan([
        ("S1", StopAction.PICKUP, "TP001"),
    ])
    orders = _make_orders([
        ("TP001", OrderType.PICKUP, 60, CargoSource.SHIPMENT),
    ])

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100, initial_cargo_load=50)
    assert not valid
    assert reason == "CARGO_CAPACITY_EXCEEDED"


# ── CASE 8: 混合 PRELOADED + SHIPMENT ─────────────────────────

def test_case8_mixed_sources():
    """PRELOADED DELIVERY + SHIPMENT PICKUP/DELIVERY 混合。"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "D1"),     # PRELOADED
        ("S2", StopAction.PICKUP, "TP001"),   # SHIPMENT
        ("S3", StopAction.DELIVER, "TP001"),  # SHIPMENT
    ])
    orders = _make_orders([
        ("D1", OrderType.DELIVERY, 30, CargoSource.PRELOADED),
        ("TP001", OrderType.PICKUP, 20, CargoSource.SHIPMENT),
    ])
    orders["TP001-D"] = PlanOrder(
        orderId="TP001", orderType=OrderType.DELIVERY,
        stationId="S3", itemCount=20, cargoSource=CargoSource.SHIPMENT,
    )

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100, initial_cargo_load=50)
    assert valid, f"混合源应 PASS，实际 {reason}"


# ── CASE 9: PRELOADED 全量派送 ────────────────────────────────

def test_case9_preloaded_full_delivery():
    """initialCargo=100, PRELOADED DELIVERY 100 → PASS"""
    plan = _make_plan([
        ("S1", StopAction.DELIVER, "D1"),
    ])
    orders = _make_orders([
        ("D1", OrderType.DELIVERY, 100, CargoSource.PRELOADED),
    ])

    valid, reason = validate_cargo_load(plan, orders, cargo_capacity=100, initial_cargo_load=100)
    assert valid, f"全量派送应 PASS，实际 {reason}"
