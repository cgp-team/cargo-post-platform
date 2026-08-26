"""Phase 1：Solver 稳定性复现测试。

目标：验证 OR-Tools pywrapcp 在各种边界条件下不会导致 Python 进程崩溃。
任何 CASE 都不能导致进程 crash。

测试矩阵：
- CASE 1: 正常可行
- CASE 2: 车辆容量不足
- CASE 3: 无车辆
- CASE 4: 固定车辆 VehicleVar（骨架）
- CASE 5: 骨架不可行
- CASE 6: 明确无解时间（距离过大）
- CASE 7: 同站 BOARD/ALIGHT
- CASE 8: 多个车辆
"""

from __future__ import annotations

import sys
from datetime import datetime

import pytest
from ortools.constraint_solver import routing_enums_pb2

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import SolveOutcome, solve

# ── 测试基础设施 ──────────────────────────────────────────────

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId="S1", longitude=104.010, latitude=30.010),
    Station(stationId="S2", longitude=104.020, latitude=30.020),
    Station(stationId="S3", longitude=104.030, latitude=30.030),
    Station(stationId="S4", longitude=104.040, latitude=30.040),
]

BATCH_START = "2026-08-26T08:00:00+08:00"
BATCH_END = "2026-08-26T12:00:00+08:00"


def _request(
    orders: list[PlanOrder],
    vehicles: list[Vehicle],
    stations: list[Station] | None = None,
    batch_start: str = BATCH_START,
    batch_end: str = BATCH_END,
) -> PlanRequest:
    return PlanRequest(
        requestId="repro-test",
        batchStart=batch_start,
        batchEnd=batch_end,
        depot=DEPOT,
        stations=stations or STATIONS,
        vehicles=vehicles,
        orders=orders,
    )


def _assert_no_crash(outcome: SolveOutcome) -> None:
    """核心断言：Solver 返回了结果，进程没有崩溃。"""
    assert outcome.status in ("feasible", "infeasible"), (
        f"Solver 返回了非法状态: {outcome.status}"
    )


# ── CASE 1: 正常可行 ──────────────────────────────────────────

def test_case1_normal_feasible():
    """最简单的可行场景：1 乘客 + 1 派送，1 车。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 1


# ── CASE 2: 车辆容量不足 ─────────────────────────────────────

def test_case2_capacity_insufficient():
    """乘客数 > 车辆容量，应返回 infeasible/OVER_CAPACITY。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2")
        for i in range(6)
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "OVER_CAPACITY"


# ── CASE 3: 无车辆 ───────────────────────────────────────────

def test_case3_no_vehicles():
    """无车辆但有订单，应返回 infeasible。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]
    vehicles: list[Vehicle] = []
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "infeasible"


# ── CASE 4: 固定车辆 VehicleVar（骨架） ──────────────────────

def test_case4_skeleton_vehicle_var():
    """骨架约束：车辆必须经停指定站点，VehicleVar 固定到指定车辆。"""
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S1", "S2", "S3"]),
    ]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    if outcome.status == "feasible":
        plan = outcome.vehicle_plans[0]
        pass_stops = [s for s in plan.stops if s.action == StopAction.PASS]
        station_ids = [s.stationId for s in pass_stops]
        assert station_ids == ["S1", "S2", "S3"], f"骨架顺序应保持: {station_ids}"


# ── CASE 5: 骨架不可行 ───────────────────────────────────────

def test_case5_skeleton_infeasible():
    """骨架站点不在 station_map 中，骨架被跳过，应不崩溃。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S99"]),  # S99 不存在
    ]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    # S99 不在 station_map 中，骨架被跳过，应可行


# ── CASE 6: 明确无解（极端距离） ─────────────────────────────

def test_case6_infeasible_geometry():
    """构造一个几何上可能无解的场景（大量分散订单，车辆不足）。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{i % 4 + 1}", alightingStationId="S1")
        for i in range(12)
    ]
    # 只给 1 辆车，容量 5，12 乘客 > 5
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "infeasible"


# ── CASE 7: 同站 BOARD/ALIGHT ────────────────────────────────

def test_case7_same_station_board_alight():
    """乘客在同一站点上下车（边界情况），不应崩溃。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S1"),
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    # 同站上下车：可能可行（距离=0）或 infeasible，但不能崩溃


# ── CASE 8: 多个车辆 ─────────────────────────────────────────

def test_case8_multiple_vehicles():
    """多车辆场景：验证 VehicleVar 不会因多车而崩溃。"""
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{i % 4 + 1}", alightingStationId="S1")
        for i in range(8)
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4),
        Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=4),
        Vehicle(vehicleId=3, passengerCapacity=5, cargoCapacity=4),
    ]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "feasible"
    # 应该只用 1-2 辆车（固定成本优先单车）
    assert len(outcome.vehicle_plans) <= 2


# ── CASE 9: 空订单列表 ───────────────────────────────────────

def test_case9_empty_orders():
    """无订单，应返回 feasible（空方案）。"""
    orders: list[PlanOrder] = []
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "feasible"


# ── CASE 10: 混合订单类型 ────────────────────────────────────

def test_case10_mixed_order_types():
    """客运 + 派送 + 揽收混合，验证各维度不互相干扰。"""
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=2),
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)]
    outcome = solve(_request(orders, vehicles))
    _assert_no_crash(outcome)
    assert outcome.status == "feasible"


# ── CASE 11: 多车骨架隔离 ────────────────────────────────────

def test_case11_multi_vehicle_skeleton_isolation():
    """两辆车各有独立骨架，互不污染。"""
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S3", itemCount=1),
    ]
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(6)
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2"]),
        Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S3", "S4", "S5"]),
    ]
    # depot 需要在 stations 里
    depot = stations[0]
    outcome = solve(_request(orders, vehicles, stations=stations))
    _assert_no_crash(outcome)


# ── CASE 12: VehicleVar 不能当数值 ───────────────────────────

def test_case12_vehicle_var_is_constraint():
    """验证 VehicleVar 返回 Constraint/IntExpr，正确用法是 solver.Add()。

    注意：OR-Tools SWIG 绑定中 sum() 可能不抛 TypeError（Python int 和 SWIG 对象的
    加法可能静默返回错误结果），因此这里只验证正确用法能工作。
    """
    from ortools.constraint_solver import pywrapcp

    manager = pywrapcp.RoutingIndexManager(3, 1, 0)
    routing = pywrapcp.RoutingModel(manager)
    solver = routing.solver()

    # VehicleVar 返回的是 Constraint/IntExpr，不是 int
    vehicle_var = routing.VehicleVar(manager.NodeToIndex(0))
    assert vehicle_var is not None

    # 正确用法：solver.Add(VehicleVar(...) == value)
    constraint = vehicle_var == 0
    solver.Add(constraint)

    # VehicleVar 的 Value() 在 Solve 后可取值
    search_parameters = pywrapcp.DefaultRoutingSearchParameters()
    search_parameters.first_solution_strategy = (
        routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    )
    solution = routing.SolveWithParameters(search_parameters)
    assert solution is not None
    assert solution.Value(vehicle_var) == 0


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    # 运行所有测试，任何一个崩溃都说明稳定性问题
    pytest.main([__file__, "-v", "--tb=short"])
