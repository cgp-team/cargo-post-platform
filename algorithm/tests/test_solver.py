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
    """按累计口径统计单车次载客/载货量：座位与仓位在批次内不复用。"""
    passenger_total = sum(1 for stop in plan.stops if stop.action == StopAction.BOARD)
    cargo_total = sum(
        orders_by_id[stop.orderId].itemCount
        for stop in plan.stops
        if stop.action in (StopAction.DELIVER, StopAction.PICKUP)
    )
    return passenger_total, cargo_total


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
