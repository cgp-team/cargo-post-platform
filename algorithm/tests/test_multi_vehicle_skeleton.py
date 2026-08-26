"""Phase 7：公交骨架多车隔离测试。

目标：验证多辆车各有独立骨架，互不污染。

场景：
  Vehicle A: skeleton [A, B, C, D]
  Vehicle B: skeleton [X, Y, Z]

  要求：两个骨架互不污染。
"""

from __future__ import annotations

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_two_vehicles_independent_skeletons():
    """两辆车各有独立骨架，互不污染。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(8)
    ]
    station_map = {s.stationId: s for s in stations}

    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
        Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S4", "S5", "S6", "S7"]),
    ]

    req = PlanRequest(
        requestId="skeleton-test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible", f"多车骨架应可行: {outcome.reason_code}"

    # 验证骨架隔离
    for plan in outcome.vehicle_plans:
        pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
        pass_station_ids = [s.stationId for s in pass_stops]

        if plan.vehicleId == 1:
            # Vehicle 1 的骨架必须是 S0→S1→S2→S3
            assert pass_station_ids == ["S0", "S1", "S2", "S3"], (
                f"Vehicle 1 骨架应为 S0→S1→S2→S3，实际 {pass_station_ids}"
            )
        elif plan.vehicleId == 2:
            # Vehicle 2 的骨架必须是 S4→S5→S6→S7
            assert pass_station_ids == ["S4", "S5", "S6", "S7"], (
                f"Vehicle 2 骨架应为 S4→S5→S6→S7，实际 {pass_station_ids}"
            )


def test_skeleton_with_cargo_insertion():
    """骨架站之间可以插入货运 stop。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(6)
    ]

    orders = [
        # 货运 stop 在骨架站之间
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),  # S4 不在骨架中
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="cargo-insert-test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible", f"骨架+货运插入应可行: {outcome.reason_code}"

    plan = outcome.vehicle_plans[0]

    # 骨架 PASS 按序出现
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    pass_station_ids = [s.stationId for s in pass_stops]
    assert pass_station_ids == ["S0", "S1", "S2", "S3"]

    # 货运 stop 出现在路线中
    cargo_stops = [s for s in plan.stops if s.orderId == "K1"]
    assert len(cargo_stops) == 1
    assert cargo_stops[0].stationId == "S4"


def test_skeleton_pass_in_correct_order():
    """骨架 PASS 必须按顺序出现，不能乱序。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3"),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3", "S4"]),
    ]

    req = PlanRequest(
        requestId="order-test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    pass_station_ids = [s.stationId for s in pass_stops]
    assert pass_station_ids == ["S0", "S1", "S2", "S3", "S4"]


def test_skeleton_vehicle_must_use_assigned_vehicle():
    """骨架站点必须由指定车辆服务，不能转给其他车。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(6)
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]

    # Vehicle 1 有骨架，Vehicle 2 无骨架
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2"]),
        Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=4),
    ]

    req = PlanRequest(
        requestId="vehicle-assign-test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    # 找到 Vehicle 1 的方案
    v1_plan = next(p for p in outcome.vehicle_plans if p.vehicleId == 1)
    pass_stops = [s for s in v1_plan.stops if s.action == StopAction.PASS]
    pass_station_ids = [s.stationId for s in pass_stops]
    assert pass_station_ids == ["S0", "S1", "S2"]


def test_skeleton_with_passenger_and_cargo():
    """骨架 + 乘客 + 货运同时存在。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(6)
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S2", itemCount=1),
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="mixed-test",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]

    # 骨架 PASS 按序
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    pass_station_ids = [s.stationId for s in pass_stops]
    assert pass_station_ids == ["S0", "S1", "S2", "S3"]

    # 乘客和货运 stop 都存在
    order_stops = {s.orderId for s in plan.stops if s.orderId}
    assert "P1" in order_stops
    assert "D1" in order_stops
    assert "K1" in order_stops


def test_empty_skeleton_ignored():
    """空骨架列表等同于无骨架。"""
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(3)
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=[]),  # 空骨架
    ]

    req = PlanRequest(
        requestId="empty-skeleton",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    # 无 PASS 节点
    plan = outcome.vehicle_plans[0]
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    assert len(pass_stops) == 0
