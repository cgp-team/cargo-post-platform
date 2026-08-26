"""P1-D：Cargo Pair 设计测试（目标行为）。

当前 API 不支持 shipmentId 配对，这些测试记录未来目标行为。

目标模型：
  TP001: PICKUP → DELIVERY（同一货物）
  要求：PICKUP 在 DELIVERY 之前、同车、currentCargoLoad 正确

当前限制：
  - PlanOrder 只有 orderType=PICKUP/DELIVERY，没有 shipmentId
  - 无法表达"同一货物的 PICKUP 和 DELIVERY"
  - 无法强制同车约束
  - 无法验证 pickup-before-delivery 配对

测试策略：
  - 用相同 orderId 模拟配对（当前 API 允许，但无配对语义）
  - 记录 NOT_SUPPORTED_YET 的行为
"""

from __future__ import annotations

import pytest

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_same_order_id_pickup_delivery_feasible():
    """同一 orderId 的 PICKUP + DELIVERY 当前是可行的（但无配对语义）。

    当前行为：两个独立 order，solver 可以自由安排顺序。
    目标行为：PICKUP 必须在 DELIVERY 之前，且同车。
    """
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    # 用相同 orderId 模拟配对
    orders = [
        PlanOrder(orderId="TP001", orderType=OrderType.PICKUP,
                  stationId="S1", itemCount=5),
        PlanOrder(orderId="TP001", orderType=OrderType.DELIVERY,
                  stationId="S2", itemCount=5),
    ]

    req = PlanRequest(
        requestId="cargo-pair-feasible",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=orders,
    )

    outcome = solve(req)
    # 当前：可能可行（solver 自由安排）
    # 目标：PICKUP 在 DELIVERY 之前 → FEASIBLE
    if outcome.status == "feasible":
        plan = outcome.vehicle_plans[0]
        tp001_stops = [s for s in plan.stops if s.orderId == "TP001"]
        # 记录实际顺序（当前不保证）
        actions = [s.action.value for s in tp001_stops]
        # 当前不强制 PICKUP-before-DELIVERY，只记录
        assert len(tp001_stops) == 2


def test_reverse_order_concept():
    """设计意图：DELIVERY before PICKUP（无初始货物）应 INFEASIBLE。

    当前行为：两个独立 order，solver 可以自由安排。
    目标行为：如果没有 initialCargo，DELIVERY before PICKUP → CARGO_LOAD_NEGATIVE。

    注意：当前 CargoOut 维度允许 DELIVERY-before-PICKUP（出程派送语义），
    所以此测试记录的是"未来 shipment pair 模型"的行为。
    """
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    # DELIVERY 先于 PICKUP（同一 orderId）
    orders = [
        PlanOrder(orderId="TP001", orderType=OrderType.DELIVERY,
                  stationId="S1", itemCount=5),
        PlanOrder(orderId="TP001", orderType=OrderType.PICKUP,
                  stationId="S2", itemCount=5),
    ]

    req = PlanRequest(
        requestId="cargo-pair-reverse",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=orders,
    )

    outcome = solve(req)
    # 当前：可能可行（CargoOut 允许 DELIVERY-before-PICKUP）
    # 目标：INFEASIBLE（shipment pair 要求 PICKUP 在 DELIVERY 之前）
    # 记录当前行为，不强制断言
    assert outcome.status in ("feasible", "infeasible")


def test_different_vehicles_concept():
    """设计意图：同一货物的 PICKUP 和 DELIVERY 在不同车辆 → INFEASIBLE。

    当前行为：两个独立 order，solver 可以分配到不同车辆。
    目标行为：同一 shipmentId 必须同车。

    注意：当前 API 无法表达同车约束，此测试记录设计意图。
    """
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    # 两个独立 order（不同 orderId，模拟不同车辆场景）
    orders = [
        PlanOrder(orderId="TP001-P", orderType=OrderType.PICKUP,
                  stationId="S1", itemCount=5),
        PlanOrder(orderId="TP001-D", orderType=OrderType.DELIVERY,
                  stationId="S2", itemCount=5),
    ]

    req = PlanRequest(
        requestId="cargo-pair-vehicles",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[
            Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10),
            Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=10),
        ],
        orders=orders,
    )

    outcome = solve(req)
    # 当前：可能分配到不同车辆
    # 目标：同一 shipmentId 必须同车 → 如果不同车辆则 INFEASIBLE
    if outcome.status == "feasible":
        plan = outcome.vehicle_plans[0]
        # 记录实际分配（当前不保证同车）
        served = {s.orderId for s in plan.stops if s.orderId}
        # 不强制断言，只记录行为


def test_cargo_load_tracking_concept():
    """设计意图：验证 currentCargoLoad 正确跟踪。

    PICKUP +5 → currentCargo=5
    DELIVERY -3 → currentCargo=2
    PICKUP +10 → currentCargo=12
    DELIVERY -12 → currentCargo=0

    当前 CargoOut/CargoIn 双维度独立累计，不跟踪 currentCargoLoad。
    此测试记录目标行为。
    """
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S1", itemCount=5),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S2", itemCount=3),
    ]

    req = PlanRequest(
        requestId="cargo-load-tracking",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    # 当前：CargoIn 维度保证 PICKUP 累计 ≤ capacity
    # 目标：currentCargoLoad 从 PICKUP 到 DELIVERY 期间正确跟踪
    plan = outcome.vehicle_plans[0]
    cargo_stops = [s for s in plan.stops if s.orderId and s.action in (StopAction.PICKUP, StopAction.DELIVER)]
    assert len(cargo_stops) == 2
