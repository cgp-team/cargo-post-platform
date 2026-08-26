"""Phase 14：最终 E2E 算法场景。

固定场景：
  公交骨架：Depot → A → B → C → D → Depot
  乘客：P001 A→C, P002 B→D
  货物：TP001 A→B, TP002 B→D
  车辆：SH003 passengerCapacity=20, cargoCapacity=300
  任务段：09:00 - 12:00

期望：算法一次性输出完整任务段，不在 A/B/C/D 之间重新调度。
"""

from __future__ import annotations

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_e2e_bus_cargo_scenario():
    """完整 E2E 场景：公交骨架 + 乘客 + 货运 + 时间窗口。"""
    # 站点：Depot + A + B + C + D
    depot = Station(stationId="DEPOT", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId="A", longitude=104.010, latitude=30.010),
        Station(stationId="B", longitude=104.020, latitude=30.020),
        Station(stationId="C", longitude=104.030, latitude=30.030),
        Station(stationId="D", longitude=104.040, latitude=30.040),
    ]

    # 乘客
    orders = [
        PlanOrder(orderId="P001", orderType=OrderType.PASSENGER,
                  boardingStationId="A", alightingStationId="C"),
        PlanOrder(orderId="P002", orderType=OrderType.PASSENGER,
                  boardingStationId="B", alightingStationId="D"),
        # 货物
        PlanOrder(orderId="TP001", orderType=OrderType.DELIVERY,
                  stationId="B", itemCount=1),
        PlanOrder(orderId="TP002", orderType=OrderType.DELIVERY,
                  stationId="D", itemCount=1),
    ]

    # 车辆：骨架 Depot→A→B→C→D
    vehicles = [
        Vehicle(
            vehicleId=1003,
            passengerCapacity=20,
            cargoCapacity=300,
            skeleton=["DEPOT", "A", "B", "C", "D"],
        ),
    ]

    req = PlanRequest(
        requestId="e2e-scenario",
        batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=depot,
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)

    # 基本断言
    assert outcome.status == "feasible", f"应可行: {outcome.reason_code}"
    assert len(outcome.vehicle_plans) == 1, "单车辆方案"
    assert outcome.total_distance > 0

    plan = outcome.vehicle_plans[0]
    assert plan.vehicleId == 1003

    # 验证闭环
    assert plan.stops[0].action == StopAction.DEPART
    assert plan.stops[0].stationId == "DEPOT"
    assert plan.stops[-1].action == StopAction.RETURN
    assert plan.stops[-1].stationId == "DEPOT"

    # 验证骨架 PASS 按序
    pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
    pass_ids = [s.stationId for s in pass_stops]
    assert pass_ids == ["DEPOT", "A", "B", "C", "D"], f"骨架应按序: {pass_ids}"

    # 验证所有订单都被服务
    served_orders = {s.orderId for s in plan.stops if s.orderId}
    assert "P001" in served_orders
    assert "P002" in served_orders
    assert "TP001" in served_orders
    assert "TP002" in served_orders

    # 验证乘客载荷不超限
    load = 0
    peak = 0
    for stop in plan.stops:
        if stop.action == StopAction.BOARD:
            load += 1
        elif stop.action == StopAction.ALIGHT:
            load -= 1
        peak = max(peak, load)
    assert peak <= 20, f"乘客峰值 {peak} 超限"

    # 验证货运 stop 字段完整
    for stop in plan.stops:
        if stop.orderId and stop.action in (StopAction.PICKUP, StopAction.DELIVER):
            assert stop.accepted is True
            assert stop.serviceMode == "NEAREST_STATION"
            assert stop.detourDistance is not None
            assert stop.detourDuration is not None

    # 验证总距离 = 所有 segmentDistance 之和
    segment_sum = round(sum(s.segmentDistance for s in plan.stops), 3)
    assert segment_sum == plan.totalDistance


def test_e2e_multi_vehicle():
    """E2E 场景：多车辆 + 骨架 + 乘客超单车容量。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(1, 6)
    ]

    # 8 乘客，单车容量 5 → 需要 2 辆车
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3")
        for i in range(8)
    ]
    orders.append(
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY, stationId="S4", itemCount=1)
    )

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
        Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S4", "S5"]),
    ]

    req = PlanRequest(
        requestId="e2e-multi",
        batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=depot,
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible", f"应可行: {outcome.reason_code}"

    # 所有订单都被服务
    served = {s.orderId for p in outcome.vehicle_plans for s in p.stops if s.orderId}
    assert served == {f"P{i}" for i in range(8)} | {"D1"}

    # 每辆车峰值载客不超限
    for plan in outcome.vehicle_plans:
        load = 0
        peak = 0
        for stop in plan.stops:
            if stop.action == StopAction.BOARD:
                load += 1
            elif stop.action == StopAction.ALIGHT:
                load -= 1
            peak = max(peak, load)
        assert peak <= 5, f"车辆 {plan.vehicleId} 峰值 {peak} 超限"


def test_e2e_with_initial_load():
    """E2E 场景：初始乘客载荷。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(1, 4)
    ]

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="P2", orderType=OrderType.PASSENGER,
                  boardingStationId="S2", alightingStationId="S3"),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=10, cargoCapacity=4,
                initialPassengerLoad=3,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="e2e-initial",
        batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=depot,
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]

    # 验证峰值载客：初始 3 + P1 上 1 = 4, P1 下 = 3, P2 上 = 4
    load = 3  # initial
    peak = load
    for stop in plan.stops:
        if stop.action == StopAction.BOARD:
            load += 1
        elif stop.action == StopAction.ALIGHT:
            load -= 1
        peak = max(peak, load)
    assert peak <= 10
