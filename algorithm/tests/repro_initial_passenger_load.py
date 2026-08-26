"""Phase 3：初始乘客载荷复现实验。

目标：验证 fix_start_cumul_to_0=False + SetRange(initialLoad, initialLoad) 的正确行为。

场景：
  Vehicle capacity = 10, initialPassengerLoad = 4
  A ALIGHT 2 → cumul = 4-2 = 2
  B BOARD 5  → cumul = 2+5 = 7
  C BOARD 4  → cumul = 7+4 = 11 > 10 → INFEASIBLE

OR-Tools 方案：
  AddDimensionWithVehicleCapacity(callback, 0, capacities, False, "Passenger")
  然后对每辆车的 start index 设置 cumul 范围。
"""

from __future__ import annotations

import pytest
from ortools.constraint_solver import pywrapcp, routing_enums_pb2


def _build_model_with_initial_load(
    demands: list[int],
    capacities: list[int],
    initial_loads: list[int],
) -> tuple[pywrapcp.RoutingModel, pywrapcp.RoutingIndexManager]:
    """构建带初始载荷的 RoutingModel。

    demands: 每个非 depot 节点的 demand
    capacities: 每辆车的容量
    initial_loads: 每辆车的初始载荷
    """
    num_nodes = 1 + len(demands)
    num_vehicles = len(capacities)

    manager = pywrapcp.RoutingIndexManager(num_nodes, num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_demands = [0] + demands

    def demand_callback(from_index: int) -> int:
        return all_demands[manager.IndexToNode(from_index)]

    cb = routing.RegisterUnaryTransitCallback(demand_callback)

    # fix_start_cumul_to_0=False：不固定 start cumul，允许设置初始载荷
    routing.AddDimensionWithVehicleCapacity(
        cb, 0, capacities, False, "Passenger",
    )

    dim = routing.GetDimensionOrDie("Passenger")

    # 设置每辆车的初始 cumul 范围
    for v, initial_load in enumerate(initial_loads):
        start_idx = routing.Start(v)
        dim.CumulVar(start_idx).SetRange(initial_load, initial_load)

    return routing, manager


def _solve_and_validate(
    routing: pywrapcp.RoutingModel,
    manager: pywrapcp.RoutingIndexManager,
    num_vehicles: int,
    capacities: list[int],
    initial_loads: list[int],
) -> dict | None:
    """求解并验证。返回 cumul 数据或 None（无解）。"""
    sp = pywrapcp.DefaultRoutingSearchParameters()
    sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC

    solution = routing.SolveWithParameters(sp)
    if solution is None:
        return None

    dim = routing.GetDimensionOrDie("Passenger")
    result: dict = {"vehicles": {}}

    for v in range(num_vehicles):
        idx = routing.Start(v)
        cumuls: list[int] = []
        while True:
            cumuls.append(solution.Value(dim.CumulVar(idx)))
            if routing.IsEnd(idx):
                break
            idx = solution.Value(routing.NextVar(idx))

        # Start cumul 必须等于 initial_load
        assert cumuls[0] == initial_loads[v], (
            f"Vehicle {v}: start cumul 应为 {initial_loads[v]}，实际 {cumuls[0]}"
        )

        # 所有 cumul 在 [0, capacity] 范围内
        for i, c in enumerate(cumuls):
            assert 0 <= c <= capacities[v], (
                f"Vehicle {v} step {i}: cumul={c} 超出 [0, {capacities[v]}]"
            )

        result["vehicles"][v] = {"cumuls": cumuls}

    return result


# ── Case 1: 初始载荷 4, ALIGHT 2, BOARD 5, BOARD 4 ──────────

def test_case1_initial_load_alight_boards():
    """capacity=10, initial=4
    A ALIGHT 2 → 2, B BOARD 5 → 7, C BOARD 4 → 11 > 10 → INFEASIBLE
    """
    demands = [-2, 5, 4]
    routing, manager = _build_model_with_initial_load(demands, [10], [4])
    result = _solve_and_validate(routing, manager, 1, [10], [4])
    assert result is None, "cumul=11 > 10 应 INFEASIBLE"


# ── Case 2: 初始载荷 4, ALIGHT 2, BOARD 5 → FEASIBLE ────────

def test_case2_initial_load_feasible():
    """capacity=10, initial=4
    A ALIGHT 2 → 2, B BOARD 5 → 7 ≤ 10 → FEASIBLE
    """
    demands = [-2, 5]
    routing, manager = _build_model_with_initial_load(demands, [10], [4])
    result = _solve_and_validate(routing, manager, 1, [10], [4])
    assert result is not None, "应为 FEASIBLE"


# ── Case 3: 初始载荷 0（等同于 fix_start=0） ─────────────────

def test_case3_initial_load_zero():
    """initial=0 等同于旧模型的 fix_start_cumul_to_0=True。"""
    demands = [3, -1]
    routing, manager = _build_model_with_initial_load(demands, [5], [0])
    result = _solve_and_validate(routing, manager, 1, [5], [0])
    assert result is not None


# ── Case 4: 初始载荷等于容量 ──────────────────────────────────

def test_case4_initial_load_at_capacity():
    """capacity=5, initial=5。ALIGHT 2 → 3, BOARD 1 → 4 → FEASIBLE。"""
    demands = [-2, 1]
    routing, manager = _build_model_with_initial_load(demands, [5], [5])
    result = _solve_and_validate(routing, manager, 1, [5], [5])
    assert result is not None


# ── Case 5: 初始载荷 + BOARD 超容量 ──────────────────────────

def test_case5_initial_load_plus_board_over_capacity():
    """capacity=5, initial=4。BOARD 2 → 6 > 5 → INFEASIBLE。"""
    demands = [2]
    routing, manager = _build_model_with_initial_load(demands, [5], [4])
    result = _solve_and_validate(routing, manager, 1, [5], [4])
    assert result is None, "4+2=6 > 5 应 INFEASIBLE"


# ── Case 6: 纯 ALIGHT（初始有乘客） ──────────────────────────

def test_case6_pure_alight_with_initial_load():
    """capacity=5, initial=3。ALIGHT 3 → 0 → FEASIBLE。
    这是 Phase 3 的核心场景：fix_start=0 时纯 ALIGHT 会 INFEASIBLE。
    """
    demands = [-3]
    routing, manager = _build_model_with_initial_load(demands, [5], [3])
    result = _solve_and_validate(routing, manager, 1, [5], [3])
    assert result is not None, "初始载荷 3 + ALIGHT 3 应 FEASIBLE"


# ── Case 7: 纯 ALIGHT 超初始载荷 ─────────────────────────────

def test_case7_alight_exceeds_initial():
    """capacity=5, initial=2。ALIGHT 4 → -2，下界 0 阻止 → INFEASIBLE。"""
    demands = [-4]
    routing, manager = _build_model_with_initial_load(demands, [5], [2])
    result = _solve_and_validate(routing, manager, 1, [5], [2])
    assert result is None, "ALIGHT 4 > initial 2 应 INFEASIBLE"


# ── Case 8: 多车不同初始载荷 ──────────────────────────────────

def test_case8_multi_vehicle_different_initial_loads():
    """Vehicle 0: capacity=10, initial=8, ALIGHT 5
    Vehicle 1: capacity=10, initial=2, BOARD 7
    两车独立。"""
    demands = [-5, 7]
    capacities = [10, 10]
    initial_loads = [8, 2]
    routing, manager = _build_model_with_initial_load(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 2, capacities, initial_loads)
    # 可能 1 车或 2 车，取决于 Solver 分配
    assert result is not None, "多车不同初始载荷应 FEASIBLE"


# ── Case 9: 初始载荷 + PickupDelivery ─────────────────────────

def test_case9_initial_load_with_pickup_delivery():
    """capacity=10, initial=3。
    Pickup: BOARD 4, Delivery: ALIGHT 2。
    同车约束 + 初始载荷。"""
    demands = [4, -2]
    capacities = [10]
    initial_loads = [3]

    manager = pywrapcp.RoutingIndexManager(3, 1, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_demands = [0] + demands

    def demand_callback(from_index: int) -> int:
        return all_demands[manager.IndexToNode(from_index)]

    cb = routing.RegisterUnaryTransitCallback(demand_callback)
    routing.AddDimensionWithVehicleCapacity(cb, 0, capacities, False, "Passenger")

    dim = routing.GetDimensionOrDie("Passenger")
    dim.CumulVar(routing.Start(0)).SetRange(initial_loads[0], initial_loads[0])

    pickup_idx = manager.NodeToIndex(1)
    delivery_idx = manager.NodeToIndex(2)
    routing.AddPickupAndDelivery(pickup_idx, delivery_idx)
    routing.solver().Add(routing.VehicleVar(pickup_idx) == routing.VehicleVar(delivery_idx))

    sp = pywrapcp.DefaultRoutingSearchParameters()
    sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    solution = routing.SolveWithParameters(sp)
    assert solution is not None, "初始载荷 + PickupDelivery 应 FEASIBLE"


# ── Case 10: 业务场景 —— 公交车已有乘客 ──────────────────────

def test_case10_bus_with_passengers():
    """20 座公交，已有 3 名乘客（从上一站上车）。
    A ALIGHT 1 → 2
    B BOARD 15 → 17
    C ALIGHT 10 → 7
    D BOARD 5 → 12
    峰值 17 ≤ 20 → FEASIBLE。
    """
    demands = [-1, 15, -10, 5]
    routing, manager = _build_model_with_initial_load(demands, [20], [3])
    result = _solve_and_validate(routing, manager, 1, [20], [3])
    assert result is not None, "公交已有乘客场景应 FEASIBLE"


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
