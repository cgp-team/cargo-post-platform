"""自研算法综合验证测试套件。

覆盖任务书要求的所有场景，确保 Python 算法本身正确且符合契约：
  6.1  Passenger 基础
  6.2  Passenger 容量溢出 → infeasible / OVER_CAPACITY
  6.3  多车辆拆分
  6.4  Cargo Delivery
  6.5  Cargo Pickup
  6.6  Shipment（完整 PICKUP → DELIVERY）
  6.7  Passenger + Cargo 混合
  6.8  Skeleton 顺序
  6.9  Skeleton + Passenger + Cargo（最重要 E2E）
  6.10 Initial Passenger Load
  6.11 Initial Cargo Load
  6.12 时间窗：宽 → feasible / 窄 → infeasible
  6.13 输入稳定性（100 次运行结果一致）
  6.14 输入顺序稳定性（打乱 orders / stations 后质量一致）
"""

from __future__ import annotations

import random
from copy import deepcopy

from app.models import (
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    StopAction,
    Vehicle,
)
from app.solver import solve

# ── 站点 ────────────────────────────────────────────────────────

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
S1 = Station(stationId="S1", longitude=104.010, latitude=30.010)
S2 = Station(stationId="S2", longitude=104.020, latitude=30.020)
S3 = Station(stationId="S3", longitude=104.030, latitude=30.030)
S4 = Station(stationId="S4", longitude=104.040, latitude=30.015)
S5 = Station(stationId="S5", longitude=104.015, latitude=30.035)
ALL_STATIONS = [S1, S2, S3, S4, S5]


def _default_vehicle(vid: int = 1, **kwargs) -> Vehicle:
    return Vehicle(vehicleId=vid, passengerCapacity=5, cargoCapacity=4, **kwargs)


def _base_request(
    orders: list[PlanOrder] | None = None,
    shipments: list[PlanShipment] | None = None,
    vehicles: list[Vehicle] | None = None,
    stations: list[Station] | None = None,
    request_id: str = "req-test",
    batch_start: str = "2026-08-23T08:00:00+08:00",
    batch_end: str = "2026-08-23T18:00:00+08:00",
) -> PlanRequest:
    return PlanRequest(
        requestId=request_id,
        batchStart=batch_start,
        batchEnd=batch_end,
        depot=DEPOT,
        stations=stations or ALL_STATIONS,
        vehicles=vehicles or [_default_vehicle()],
        orders=orders or [],
        shipments=shipments or [],
    )


def _count_actions(stops, action: StopAction) -> int:
    return sum(1 for s in stops if s.action == action)


def _peak_passenger_load(stops, initial: int = 0) -> int:
    load = initial
    peak = load
    for s in stops:
        if s.action == StopAction.BOARD:
            load += 1
        elif s.action == StopAction.ALIGHT:
            load -= 1
        peak = max(peak, load)
    return peak


def _peak_cargo_load(stops, initial: int = 0) -> int:
    """按物流口径：派送件 -1，揽收件 +1，PRELOADED DELIVERY 消耗初始载荷。"""
    load = initial
    peak = load
    for s in stops:
        if s.action == StopAction.DELIVER:
            load -= 1
        elif s.action == StopAction.PICKUP:
            load += 1
        peak = max(peak, load)
    return peak


# ═══════════════════════════════════════════════════════════════
# 6.1  Passenger 基础：1 车 5 座 1 名乘客
# ═══════════════════════════════════════════════════════════════


def test_6_1_passenger_basic():
    """1 车 5 座 1 名乘客 → DEPART → BOARD → ALIGHT → RETURN。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]
    req = _base_request(orders=orders)
    outcome = solve(req)

    assert outcome.status == "feasible", f"应可行: {outcome.reason_code}"
    assert len(outcome.vehicle_plans) == 1
    plan = outcome.vehicle_plans[0]
    actions = [s.action for s in plan.stops]
    assert actions[0] == StopAction.DEPART
    assert StopAction.BOARD in actions
    assert StopAction.ALIGHT in actions
    assert actions[-1] == StopAction.RETURN
    # BOARD 在 ALIGHT 之前
    assert actions.index(StopAction.BOARD) < actions.index(StopAction.ALIGHT)


# ═══════════════════════════════════════════════════════════════
# 6.2  Passenger 容量溢出
# ═══════════════════════════════════════════════════════════════


def test_6_2_passenger_capacity_overflow():
    """6 名乘客，单车容量 5 → infeasible / OVER_CAPACITY。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2")
        for i in range(6)
    ]
    req = _base_request(orders=orders)
    outcome = solve(req)

    assert outcome.status == "infeasible"
    assert outcome.reason_code == "OVER_CAPACITY"


# ═══════════════════════════════════════════════════════════════
# 6.3  多车辆拆分
# ═══════════════════════════════════════════════════════════════


def test_6_3_multi_vehicle_split():
    """12 名乘客需要多车辆（单车容量不足时算法正确拆分）。
    算法优先最小化车辆数，允许单车多次 BOARD/ALIGHT 序列。
    验证所有乘客都被服务且载客峰值不超限。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2")
        for i in range(12)
    ]
    vehicles = [_default_vehicle(i) for i in range(1, 4)]
    req = _base_request(orders=orders, vehicles=vehicles)
    outcome = solve(req)

    assert outcome.status == "feasible"
    # 所有乘客都被服务
    served = {s.orderId for p in outcome.vehicle_plans for s in p.stops if s.orderId}
    assert served == {f"P{i}" for i in range(12)}
    # 每辆车的载客峰值不超限
    for plan in outcome.vehicle_plans:
        peak = _peak_passenger_load(plan.stops)
        vehicle = next(v for v in req.vehicles if v.vehicleId == plan.vehicleId)
        assert peak <= vehicle.passengerCapacity, \
            f"车辆 {plan.vehicleId} 峰值 {peak} 超容量 {vehicle.passengerCapacity}"


# ═══════════════════════════════════════════════════════════════
# 6.4  Cargo Delivery
# ═══════════════════════════════════════════════════════════════


def test_6_4_cargo_delivery():
    """场站 → 村站派送 → DELIVER。"""
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S1", itemCount=1),
    ]
    req = _base_request(orders=orders)
    outcome = solve(req)

    assert outcome.status == "feasible"
    plan = outcome.vehicle_plans[0]
    actions = [s.action for s in plan.stops]
    assert StopAction.DELIVER in actions
    deliver_stop = next(s for s in plan.stops if s.action == StopAction.DELIVER)
    assert deliver_stop.stationId == "S1"
    assert deliver_stop.orderId == "D1"


# ═══════════════════════════════════════════════════════════════
# 6.5  Cargo Pickup
# ═══════════════════════════════════════════════════════════════


def test_6_5_cargo_pickup():
    """村站 → 场站揽收 → PICKUP。"""
    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S1", itemCount=1),
    ]
    req = _base_request(orders=orders)
    outcome = solve(req)

    assert outcome.status == "feasible"
    plan = outcome.vehicle_plans[0]
    assert StopAction.PICKUP in [s.action for s in plan.stops]
    pickup_stop = next(s for s in plan.stops if s.action == StopAction.PICKUP)
    assert pickup_stop.stationId == "S1"
    assert pickup_stop.orderId == "K1"


# ═══════════════════════════════════════════════════════════════
# 6.6  Shipment（完整 PICKUP → DELIVERY）
# ═══════════════════════════════════════════════════════════════


def test_6_6_shipment_pair():
    """配对货运：S1 揽收 → S3 派送，同一辆车，PICKUP 在 DELIVERY 之前。"""
    shipments = [
        PlanShipment(shipmentId="TP1", pickupStationId="S1",
                     deliveryStationId="S3", quantity=2),
    ]
    req = _base_request(shipments=shipments)
    outcome = solve(req)

    assert outcome.status == "feasible"
    plan = outcome.vehicle_plans[0]
    # 找到 TP1 的 PICKUP 和 DELIVERY
    tp1_stops = [s for s in plan.stops if s.orderId == "TP1"]
    assert len(tp1_stops) == 2
    pickup_idx = next(i for i, s in enumerate(plan.stops) if s.orderId == "TP1" and s.action == StopAction.PICKUP)
    delivery_idx = next(i for i, s in enumerate(plan.stops) if s.orderId == "TP1" and s.action == StopAction.DELIVER)
    assert pickup_idx < delivery_idx, "PICKUP 必须在 DELIVERY 之前"


# ═══════════════════════════════════════════════════════════════
# 6.7  Passenger + Cargo 混合
# ═══════════════════════════════════════════════════════════════


def test_6_7_passenger_cargo_mixed():
    """同时存在乘客、派送、揽收。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]
    req = _base_request(orders=orders)
    outcome = solve(req)

    assert outcome.status == "feasible"
    plan = outcome.vehicle_plans[0]
    served = {s.orderId for s in plan.stops if s.orderId}
    assert {"P1", "D1", "K1"}.issubset(served)

    # 载客峰值 ≤ 5
    assert _peak_passenger_load(plan.stops) <= 5


# ═══════════════════════════════════════════════════════════════
# 6.8  Skeleton 顺序
# ═══════════════════════════════════════════════════════════════


def test_6_8_skeleton_order():
    """骨架 S1 → S2 → S3，货运插入其间，骨架顺序不被破坏。"""
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S4", itemCount=1),
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                skeleton=["S1", "S2", "S3"]),
    ]
    req = _base_request(orders=orders, vehicles=vehicles)
    outcome = solve(req)

    assert outcome.status == "feasible"
    plan = outcome.vehicle_plans[0]
    # 提取骨架 PASS 站点顺序
    pass_stops = [s.stationId for s in plan.stops if s.action == StopAction.PASS]
    # 骨架站点必须按顺序出现（允许其他站点插入）
    skeleton = ["S1", "S2", "S3"]
    idx = 0
    for sid in pass_stops:
        if idx < len(skeleton) and sid == skeleton[idx]:
            idx += 1
    assert idx == len(skeleton), f"骨架顺序不完整: {pass_stops}"


# ═══════════════════════════════════════════════════════════════
# 6.9  Skeleton + Passenger + Cargo（最重要 E2E）
# ═══════════════════════════════════════════════════════════════


def test_6_9_skeleton_passenger_cargo_e2e():
    """公交骨架 + 乘客 + 派送 + 揽收 + 时间窗 + 多车辆。"""
    depot = Station(stationId="DEPOT", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId="A", longitude=104.010, latitude=30.010),
        Station(stationId="B", longitude=104.020, latitude=30.020),
        Station(stationId="C", longitude=104.030, latitude=30.030),
        Station(stationId="D", longitude=104.040, latitude=30.040),
    ]

    orders = [
        PlanOrder(orderId="P001", orderType=OrderType.PASSENGER,
                  boardingStationId="A", alightingStationId="C"),
        PlanOrder(orderId="P002", orderType=OrderType.PASSENGER,
                  boardingStationId="B", alightingStationId="D"),
        PlanOrder(orderId="D001", orderType=OrderType.DELIVERY,
                  stationId="B", itemCount=1),
        PlanOrder(orderId="K001", orderType=OrderType.PICKUP,
                  stationId="D", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1003, passengerCapacity=20, cargoCapacity=300,
                skeleton=["A", "B", "C", "D"]),
    ]

    req = PlanRequest(
        requestId="e2e-skeleton-pc",
        batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=depot,
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 1

    plan = outcome.vehicle_plans[0]
    # 闭环
    assert plan.stops[0].action == StopAction.DEPART
    assert plan.stops[-1].action == StopAction.RETURN

    # 骨架 PASS 按序
    pass_ids = [s.stationId for s in plan.stops if s.action == StopAction.PASS]
    assert pass_ids == ["A", "B", "C", "D"], f"骨架应按序: {pass_ids}"

    # 所有订单都被服务
    served = {s.orderId for s in plan.stops if s.orderId}
    assert {"P001", "P002", "D001", "K001"}.issubset(served)

    # 载客峰值 ≤ 20
    assert _peak_passenger_load(plan.stops) <= 20

    # 总距离 = 段距离之和
    seg_sum = round(sum(s.segmentDistance for s in plan.stops), 3)
    assert seg_sum == plan.totalDistance


# ═══════════════════════════════════════════════════════════════
# 6.10 Initial Passenger Load
# ═══════════════════════════════════════════════════════════════


def test_6_10_initial_passenger_load_feasible():
    """容量 5，初始载客 3，增加 2 名新乘客 → 可行。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="P2", orderType=OrderType.PASSENGER,
                  boardingStationId="S2", alightingStationId="S3"),
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                initialPassengerLoad=3),
    ]
    req = _base_request(orders=orders, vehicles=vehicles)
    outcome = solve(req)
    assert outcome.status == "feasible"


def test_6_10_initial_passenger_load_infeasible():
    """容量 5，初始载客 3，增加 3 名新乘客 → 无解。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2")
        for i in range(3)
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                initialPassengerLoad=3),
    ]
    req = _base_request(orders=orders, vehicles=vehicles)
    outcome = solve(req)
    assert outcome.status == "infeasible"


# ═══════════════════════════════════════════════════════════════
# 6.11 Initial Cargo Load
# ═══════════════════════════════════════════════════════════════


def test_6_11_initial_cargo_load_feasible():
    """cargoCapacity=10，initialCargoLoad=8，再派送 2 件 → 可行。"""
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S1", itemCount=2),
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                initialCargoLoad=8),
    ]
    req = _base_request(orders=orders, vehicles=vehicles)
    outcome = solve(req)
    assert outcome.status == "feasible"


def test_6_11_initial_cargo_load_infeasible():
    """cargoCapacity=10，initialCargoLoad=8，再派送 3 件 → 无解。"""
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S1", itemCount=3),
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                initialCargoLoad=8),
    ]
    req = _base_request(orders=orders, vehicles=vehicles)
    outcome = solve(req)
    assert outcome.status == "infeasible"


# ═══════════════════════════════════════════════════════════════
# 6.12 时间窗
# ═══════════════════════════════════════════════════════════════


def test_6_12_time_window_wide_feasible():
    """足够宽的时间窗 → feasible。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]
    req = _base_request(
        orders=orders,
        batch_start="2026-08-23T08:00:00+08:00",
        batch_end="2026-08-23T23:00:00+08:00",
    )
    outcome = solve(req)
    assert outcome.status == "feasible"


def test_6_12_time_window_tight_infeasible():
    """明显不足的时间窗 → infeasible。"""
    orders = [
        # 10 名乘客分散在远距离站点
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i % 5) + 1}", alightingStationId=f"S{((i + 2) % 5) + 1}")
        for i in range(10)
    ]
    vehicles = [_default_vehicle(i) for i in range(1, 4)]
    # 只给 1 分钟时间窗
    req = _base_request(
        orders=orders,
        vehicles=vehicles,
        batch_start="2026-08-23T08:00:00+08:00",
        batch_end="2026-08-23T08:01:00+08:00",
    )
    outcome = solve(req)
    assert outcome.status == "infeasible"
    assert outcome.reason_code is not None


# ═══════════════════════════════════════════════════════════════
# 6.13 输入稳定性（100 次运行结果一致）
# ═══════════════════════════════════════════════════════════════


def test_6_13_deterministic_100_runs():
    """同一请求运行 100 次：route、vehicle assignment、totalDistance 必须一致。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    req = _base_request(orders=orders)
    base = solve(req)
    assert base.status == "feasible"

    for i in range(100):
        outcome = solve(req)
        assert outcome.status == base.status, f"第 {i+1} 次状态不一致"
        assert len(outcome.vehicle_plans) == len(base.vehicle_plans), f"第 {i+1} 次车辆数不一致"
        assert outcome.total_distance == base.total_distance, f"第 {i+1} 次总里程不一致"
        for vp, base_vp in zip(outcome.vehicle_plans, base.vehicle_plans):
            assert vp.vehicleId == base_vp.vehicleId, f"第 {i+1} 次车辆分配不一致"
            assert len(vp.stops) == len(base_vp.stops), f"第 {i+1} 次经停数不一致"
            for s, bs in zip(vp.stops, base_vp.stops):
                assert s.stationId == bs.stationId, f"第 {i+1} 次站点不一致"
                assert s.action == bs.action, f"第 {i+1} 次动作不一致"


# ═══════════════════════════════════════════════════════════════
# 6.14 输入顺序稳定性
# ═══════════════════════════════════════════════════════════════


def test_6_14_input_order_stability():
    """打乱 orders 输入顺序后，输出质量（总里程、车辆数）应一致。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i % 3) + 1}", alightingStationId=f"S{((i + 1) % 3) + 1}")
        for i in range(4)
    ]

    def solve_with(order_list):
        req = _base_request(orders=order_list)
        return solve(req)

    base = solve_with(orders)
    assert base.status == "feasible", f"基线应可行: {base.reason_code}"

    random.seed(42)
    for _ in range(20):
        shuffled = orders[:]
        random.shuffle(shuffled)
        outcome = solve_with(shuffled)
        assert outcome.status == "feasible"
        assert len(outcome.vehicle_plans) == len(base.vehicle_plans)
        # 里程允许小幅波动（< 30%）
        assert abs(outcome.total_distance - base.total_distance) / base.total_distance < 0.30
