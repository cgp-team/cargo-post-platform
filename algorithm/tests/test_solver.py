"""OR-Tools 求解器单元测试：时序、容量、闭环、用车数、无解口径、确定性与性能。"""

from __future__ import annotations

import time
from math import hypot

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId="S1", longitude=104.010, latitude=30.010),
    Station(stationId="S2", longitude=104.020, latitude=30.020),
    Station(stationId="S3", longitude=104.030, latitude=30.030),
    Station(stationId="S4", longitude=104.040, latitude=30.015),
    Station(stationId="S5", longitude=104.015, latitude=30.035),
    Station(stationId="S6", longitude=104.025, latitude=30.005),
    Station(stationId="S7", longitude=104.035, latitude=30.040),
    Station(stationId="S8", longitude=104.005, latitude=30.025),
    Station(stationId="S9", longitude=104.045, latitude=30.045),
    Station(stationId="S10", longitude=104.050, latitude=30.010),
]


def passenger_order(index: int, board: str = "S1", alight: str = "S2") -> PlanOrder:
    return PlanOrder(
        orderId=f"O-P{index}",
        orderType=OrderType.PASSENGER,
        boardingStationId=board,
        alightingStationId=alight,
    )


def cargo_order(index: int, order_type: OrderType = OrderType.DELIVERY, station: str = "S3", items: int = 1) -> PlanOrder:
    return PlanOrder(
        orderId=f"O-{order_type.value[0]}{index}",
        orderType=order_type,
        stationId=station,
        itemCount=items,
    )


def make_request(orders: list[PlanOrder], vehicle_count: int = 1, request_id: str = "req-test") -> PlanRequest:
    return PlanRequest(
        requestId=request_id,
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T08:30:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=1000 + i) for i in range(1, vehicle_count + 1)],
        orders=orders,
    )


def simulate_loads(plan, orders_by_id: dict[str, PlanOrder]) -> tuple[int, int]:
    """按双维度口径统计单车次载客/载货峰值：载客按累计（座位批次内不复用）；
    载货 = max(累计派送件, 累计揽收件)（出程派送、返程揽收，货仓依次复用）。"""
    passenger_total = sum(1 for stop in plan.stops if stop.action == StopAction.BOARD)
    deliveries = sum(
        orders_by_id[stop.orderId].itemCount
        for stop in plan.stops if stop.action == StopAction.DELIVER
    )
    pickups = sum(
        orders_by_id[stop.orderId].itemCount
        for stop in plan.stops if stop.action == StopAction.PICKUP
    )
    return passenger_total, max(deliveries, pickups)


def test_board_before_alight() -> None:
    orders = [passenger_order(1, "S1", "S2"), passenger_order(2, "S3", "S4"), passenger_order(3, "S5", "S6")]
    outcome = solve(make_request(orders))
    assert outcome.status == "feasible"
    for plan in outcome.vehicle_plans:
        board_at: dict[str, int] = {}
        for position, stop in enumerate(plan.stops):
            if stop.action == StopAction.BOARD:
                board_at[stop.orderId] = position
            elif stop.action == StopAction.ALIGHT:
                assert stop.orderId in board_at, f"{stop.orderId} 未上车先下车"
                assert board_at[stop.orderId] < position, f"{stop.orderId} 下车早于上车"


def test_capacity_never_exceeded() -> None:
    orders = (
        [passenger_order(i, f"S{i % 9 + 1}", f"S{(i + 3) % 9 + 1}") for i in range(1, 7)]
        + [cargo_order(i, OrderType.DELIVERY, f"S{i % 9 + 1}") for i in range(1, 5)]
        + [cargo_order(i, OrderType.PICKUP, f"S{(i + 4) % 9 + 1}") for i in range(5, 9)]
    )
    request = make_request(orders, vehicle_count=2)
    outcome = solve(request)
    assert outcome.status == "feasible"
    orders_by_id = {order.orderId: order for order in orders}
    vehicles_by_id = {vehicle.vehicleId: vehicle for vehicle in request.vehicles}
    for plan in outcome.vehicle_plans:
        passenger_total, cargo_total = simulate_loads(plan, orders_by_id)
        vehicle = vehicles_by_id[plan.vehicleId]
        assert passenger_total <= vehicle.passengerCapacity, f"累计载客 {passenger_total} 超限"
        assert cargo_total <= vehicle.cargoCapacity, f"累计载货 {cargo_total} 超限"


def test_closed_loop_depart_return() -> None:
    outcome = solve(make_request([passenger_order(1), cargo_order(1), cargo_order(2, OrderType.PICKUP)]))
    assert outcome.status == "feasible"
    for plan in outcome.vehicle_plans:
        stops = plan.stops
        assert stops[0].action == StopAction.DEPART
        assert stops[0].stationId == DEPOT.stationId
        assert stops[-1].action == StopAction.RETURN
        assert stops[-1].stationId == DEPOT.stationId
        assert plan.totalDistance > 0
        # 分段里程之和等于本车总里程（3 位小数口径）
        assert round(sum(stop.segmentDistance for stop in stops), 3) == plan.totalDistance
        # 里程必须纯按欧氏直线（车辆固定成本不得摊入分段里程，防回归）
        coords = {station.stationId: station for station in STATIONS}
        coords[DEPOT.stationId] = DEPOT
        expected = sum(
            round(hypot(
                coords[a.stationId].longitude - coords[b.stationId].longitude,
                coords[a.stationId].latitude - coords[b.stationId].latitude,
            ), 3)
            for a, b in zip(stops, stops[1:])
        )
        assert round(expected, 3) == plan.totalDistance
    assert round(sum(plan.totalDistance for plan in outcome.vehicle_plans), 3) == outcome.total_distance


def test_single_vehicle_preferred_when_capacity_enough() -> None:
    orders = [passenger_order(1), cargo_order(1), cargo_order(2, OrderType.PICKUP)]
    outcome = solve(make_request(orders, vehicle_count=3))
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 1, "容量足够时必须优先单车（契约 Q7）"


def test_auto_second_vehicle_on_passenger_overload() -> None:
    # 6 名乘客 > 单车 5 人，必须自动启用第 2 辆车
    outcome = solve(make_request([passenger_order(i) for i in range(6)], vehicle_count=2))
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 2
    served = {stop.orderId for plan in outcome.vehicle_plans for stop in plan.stops if stop.orderId}
    assert served == {f"O-P{i}" for i in range(6)}


def test_auto_second_vehicle_on_cargo_overload() -> None:
    # 6 件派送 > 单车 4 件货仓，必须自动启用第 2 辆车
    orders = [cargo_order(i, OrderType.DELIVERY, f"S{i % 9 + 1}") for i in range(6)]
    outcome = solve(make_request(orders, vehicle_count=2))
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 2


def test_total_demand_over_total_capacity_infeasible() -> None:
    # 6 名乘客 > 两车合计 10 人？否——6 ≤ 10；用 16 名乘客 > 3 车合计 15 人
    outcome = solve(make_request([passenger_order(i) for i in range(16)], vehicle_count=3))
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "OVER_CAPACITY"
    assert not outcome.vehicle_plans


def test_cargo_over_total_capacity_infeasible() -> None:
    orders = [cargo_order(i, OrderType.DELIVERY, f"S{i % 9 + 1}", items=2) for i in range(7)]
    outcome = solve(make_request(orders, vehicle_count=3))  # 14 件 > 3 车合计 12 件
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "OVER_CAPACITY"


def test_net_load_capacity_reuse_single_vehicle() -> None:
    """P1-002 回归：载货净载荷（出程派送 + 返程揽收）复用货仓。

    单车容量 10，6 件揽收 + 4 件派送 + 3 件揽收 = 13 件总货量。
    旧「派送+揽收全部累计」口径下 13 > 10 会误判 OVER_CAPACITY；
    净载荷双维度口径下 派送 4 ≤ 10 且 揽收 9 ≤ 10，单车可行——这正是「闲置运力利用」。
    """
    orders = (
        [cargo_order(i, OrderType.PICKUP, f"S{i % 9 + 1}") for i in range(1, 7)]
        + [cargo_order(i, OrderType.DELIVERY, f"S{i % 9 + 1}") for i in range(7, 11)]
        + [cargo_order(i, OrderType.PICKUP, f"S{i % 9 + 1}") for i in range(11, 14)]
    )
    request = make_request(orders, vehicle_count=1)
    request.vehicles[0].cargoCapacity = 10
    outcome = solve(request)
    assert outcome.status == "feasible", f"净载荷口径下单车 13 件(派4+揽9)应可行: {outcome.reason_code}"
    assert len(outcome.vehicle_plans) == 1
    orders_by_id = {order.orderId: order for order in orders}
    for plan in outcome.vehicle_plans:
        _, cargo_max = simulate_loads(plan, orders_by_id)
        assert cargo_max <= 10, f"任一时点货件 {cargo_max} 不应超容量 10"


def test_deliveries_only_still_need_second_vehicle() -> None:
    """P1-002 回归：净载荷下纯派送仍受容量约束——6 件派送 > 单车 4 件仍须启用第 2 辆车。"""
    orders = [cargo_order(i, OrderType.DELIVERY, f"S{i % 9 + 1}") for i in range(6)]
    outcome = solve(make_request(orders, vehicle_count=2))
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 2


def test_deterministic_same_input_same_output() -> None:
    orders = (
        [passenger_order(i, f"S{i % 9 + 1}", f"S{(i + 2) % 9 + 1}") for i in range(1, 6)]
        + [cargo_order(i, OrderType.DELIVERY, f"S{i % 9 + 1}") for i in range(1, 4)]
        + [cargo_order(i, OrderType.PICKUP, f"S{(i + 5) % 9 + 1}") for i in range(4, 7)]
    )
    first = solve(make_request(orders, vehicle_count=3))
    second = solve(make_request(orders, vehicle_count=3))
    assert first.status == second.status == "feasible"
    assert first.total_distance == second.total_distance
    assert [plan.model_dump() for plan in first.vehicle_plans] == [
        plan.model_dump() for plan in second.vehicle_plans
    ]


def test_skeleton_mandatory_order_and_cargo_detour() -> None:
    """Phase 5 回归：公交骨架（Mandatory Passenger Service）车辆必须按序经停骨架站，
    货运作为绕行插入骨架间隙，且携带算法解释字段（accepted/serviceMode/detour）。"""
    orders = [
        cargo_order(1, OrderType.DELIVERY, "S4", 1),  # 绕行派送（S4 不在骨架）
        cargo_order(2, OrderType.PICKUP, "S2", 2),     # 骨架站揽收
    ]
    req = make_request(orders, vehicle_count=1)
    req.vehicles[0].skeleton = ["S1", "S2", "S3"]
    outcome = solve(req)
    assert outcome.status == "feasible", f"骨架+货运应可行: {outcome.reason_code}"
    plan = outcome.vehicle_plans[0]
    # 骨架 PASS 按序经停（不可删站/跳站）
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    assert [s.stationId for s in pass_stops] == ["S1", "S2", "S3"]
    # 货运经停带算法解释：绕行的 S4 有 detour，骨架站 S2 detour=0
    cargo_stops = {s.orderId: s for s in plan.stops if s.orderId}
    assert cargo_stops["O-D1"].accepted is True
    assert cargo_stops["O-D1"].serviceMode == "NEAREST_STATION"
    assert cargo_stops["O-D1"].servicePoint == "S4"
    assert cargo_stops["O-D1"].detourDistance is not None and cargo_stops["O-D1"].detourDistance > 0
    assert cargo_stops["O-P2"].detourDistance == 0


def test_full_scale_25_orders_under_time_limit() -> None:
    # 25 单满规模：13 客 + 6 派 + 6 揽，累计需求贴近 3 车合计容量（15 人 / 12 件）
    orders = (
        [passenger_order(i, f"S{i % 10 + 1}", f"S{(i + 4) % 10 + 1}") for i in range(1, 14)]
        + [cargo_order(i, OrderType.DELIVERY, f"S{i % 10 + 1}") for i in range(1, 7)]
        + [cargo_order(i, OrderType.PICKUP, f"S{(i + 6) % 10 + 1}") for i in range(7, 13)]
    )
    assert len(orders) == 25
    started = time.monotonic()
    outcome = solve(make_request(orders, vehicle_count=3))
    elapsed = time.monotonic() - started
    assert outcome.status == "feasible"
    assert elapsed < 10, f"25 单规模求解耗时 {elapsed:.3f}s 超过契约 10 秒时限"
