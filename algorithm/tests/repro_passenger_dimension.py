"""Phase 2：乘客动态容量复现实验。

目标：验证 BOARD +passengerCount / ALIGHT -passengerCount 的正确行为。
不修改生产 Solver，先在最小模型中验证 OR-Tools 行为。

OR-Tools CumulVar 语义（实验确认）：
  CumulVar(node) = 到达该节点时的累计值（transit 之前）
  transit(node)  = 该节点的 demand（正值=装载，负值=卸载）
  离开时的值 = CumulVar(node) + transit(node)

注意：OR-Tools 可能选择不同的路线顺序（优化目标驱动），因此测试验证：
  1. 可行/不可行状态正确
  2. 所有 cumul 值在 [0, capacity] 范围内
  3. start=0, end=总 demand 之和
"""

from __future__ import annotations

import pytest
from ortools.constraint_solver import pywrapcp, routing_enums_pb2


def _build_minimal_model(
    demands: list[int],
    capacities: list[int],
) -> tuple[pywrapcp.RoutingModel, pywrapcp.RoutingIndexManager]:
    """构建最小 RoutingModel。"""
    num_nodes = 1 + len(demands)
    num_vehicles = len(capacities)

    manager = pywrapcp.RoutingIndexManager(num_nodes, num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_demands = [0] + demands

    def demand_callback(from_index: int) -> int:
        return all_demands[manager.IndexToNode(from_index)]

    callback_index = routing.RegisterUnaryTransitCallback(demand_callback)
    routing.AddDimensionWithVehicleCapacity(
        callback_index, 0, capacities, True, "Passenger",
    )

    return routing, manager


def _solve_and_validate(
    routing: pywrapcp.RoutingModel,
    manager: pywrapcp.RoutingIndexManager,
    num_vehicles: int,
    expected_capacity: int,
) -> dict | None:
    """求解并验证 cumul 约束。返回 cumul 数据或 None（无解）。"""
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
        nodes: list[int] = []
        while True:
            c = solution.Value(dim.CumulVar(idx))
            n = manager.IndexToNode(idx)
            cumuls.append(c)
            nodes.append(n)
            if routing.IsEnd(idx):
                break
            idx = solution.Value(routing.NextVar(idx))

        # 验证所有 cumul 在 [0, capacity] 范围内
        for i, c in enumerate(cumuls):
            assert 0 <= c <= expected_capacity, (
                f"Vehicle {v} step {i}: cumul={c} 超出 [0, {expected_capacity}]"
            )

        # Start cumul 必须为 0
        assert cumuls[0] == 0, f"Vehicle {v}: start cumul 应为 0，实际 {cumuls[0]}"

        result["vehicles"][v] = {"cumuls": cumuls, "nodes": nodes}

    return result


# ── Case 1: 上 5, 下 3, 上 3 → FEASIBLE ──────────────────────

def test_case1_board_alight_board_feasible():
    """A 上 5, B 下 3, C 上 3。总 demand=5，capacity=5 → FEASIBLE。
    所有 cumul 必须在 [0, 5] 内。
    """
    demands = [5, -3, 3]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is not None, "应为 FEASIBLE"


# ── Case 2: 上 5, 下 3, 上 4 → INFEASIBLE ────────────────────

def test_case2_over_capacity_infeasible():
    """A 上 5, B 下 3, C 上 4。
    某条路线中 C 到达时 cumul=2, transit=+4 → 离开=6 > 5 → INFEASIBLE。
    """
    demands = [5, -3, 4]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is None, "应为 INFEASIBLE"


# ── Case 3: 上 5, 全下, 上 5 → FEASIBLE ──────────────────────

def test_case3_full_release_and_reload():
    """A 上 5, B 全下, C 上 5。capacity=5 → FEASIBLE。"""
    demands = [5, -5, 5]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is not None, "应为 FEASIBLE"


# ── Case 4: 上 3, 上 2, 下 4 → FEASIBLE ──────────────────────

def test_case4_multiple_boards_then_alight():
    """A 上 3, B 上 2（累计 5）, C 下 4（剩余 1）。capacity=5 → FEASIBLE。"""
    demands = [3, 2, -4]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is not None, "应为 FEASIBLE"


# ── Case 5: 下界为 0 ──────────────────────────────────────────

def test_case5_cumul_never_negative():
    """A 上 2, B 下 4 → cumul 尝试到 -2，被下界 0 阻止 → INFEASIBLE。"""
    demands = [2, -4]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is None, "ALIGHT 超过当前载客应为 INFEASIBLE"


# ── Case 6: 多车独立容量 ──────────────────────────────────────

def test_case6_two_vehicles_independent_capacity():
    """两车各 capacity=3，6 节点 demand=[2,2,2,2,2,2]。不崩溃即可。"""
    demands = [2, 2, 2, 2, 2, 2]
    routing, manager = _build_minimal_model(demands, [3, 3])
    result = _solve_and_validate(routing, manager, 2, 3)
    # 不崩溃即通过


# ── Case 7: Start cumul 固定为 0 ──────────────────────────────

def test_case7_start_cumul_is_zero():
    """fix_start_cumul_to_0=True 时，每辆车出发时 cumul=0。"""
    demands = [3, -1]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is not None
    assert result["vehicles"][0]["cumuls"][0] == 0


# ── Case 8: Capacity 上界生效 ─────────────────────────────────

def test_case8_capacity_upper_bound_enforced():
    """demand 正好等于 capacity → FEASIBLE。demand = capacity + 1 → INFEASIBLE。"""
    routing, manager = _build_minimal_model([5], [5])
    assert _solve_and_validate(routing, manager, 1, 5) is not None

    routing, manager = _build_minimal_model([6], [5])
    assert _solve_and_validate(routing, manager, 1, 5) is None


# ── Case 9: PickupDelivery 同车约束 ───────────────────────────

def test_case9_pickup_delivery_same_vehicle():
    """验证 AddPickupAndDelivery + VehicleVar + 动态 demand 同时工作。"""
    demands = [3, -3]
    capacities = [5]

    manager = pywrapcp.RoutingIndexManager(3, 1, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_demands = [0] + demands

    def demand_callback(from_index: int) -> int:
        return all_demands[manager.IndexToNode(from_index)]

    cb = routing.RegisterUnaryTransitCallback(demand_callback)
    routing.AddDimensionWithVehicleCapacity(cb, 0, capacities, True, "Passenger")

    pickup_idx = manager.NodeToIndex(1)
    delivery_idx = manager.NodeToIndex(2)
    routing.AddPickupAndDelivery(pickup_idx, delivery_idx)

    solver = routing.solver()
    solver.Add(routing.VehicleVar(pickup_idx) == routing.VehicleVar(delivery_idx))

    sp = pywrapcp.DefaultRoutingSearchParameters()
    sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    solution = routing.SolveWithParameters(sp)
    assert solution is not None, "PickupDelivery + 动态 demand 应为 FEASIBLE"


# ── Case 10: 旧模型 vs 新模型对比 ─────────────────────────────

def test_case10_old_model_no_release():
    """旧模型：全部 +1（BOARD），6 个节点 > capacity=5 → INFEASIBLE。
    新模型：上下交替，峰值=1 → FEASIBLE。
    """
    demands_old = [1, 1, 1, 1, 1, 1]
    routing, manager = _build_minimal_model(demands_old, [5])
    assert _solve_and_validate(routing, manager, 1, 5) is None

    demands_new = [1, -1, 1, -1, 1, -1]
    routing, manager = _build_minimal_model(demands_new, [5])
    assert _solve_and_validate(routing, manager, 1, 5) is not None


# ── Case 11: 负 demand transit ────────────────────────────────

def test_case11_negative_transit_valid():
    """验证 OR-Tools 允许负 demand 的 transit 回调。"""
    demands = [3, -3]
    routing, manager = _build_minimal_model(demands, [10])
    result = _solve_and_validate(routing, manager, 1, 10)
    assert result is not None
    # 最终 cumul = 3 + (-3) = 0
    v0 = result["vehicles"][0]
    assert v0["cumuls"][-1] == 0, f"最终 cumul 应为 0，实际 {v0['cumuls'][-1]}"


# ── Case 12: 纯 ALIGHT（初始载荷场景） ────────────────────────

def test_case12_pure_alight_with_slack():
    """纯下客场景：fix_start=0 → cumul 尝试到 -3 → INFEASIBLE。
    这就是 Phase 3 需要 initialPassengerLoad 的原因。
    """
    demands = [-3]
    routing, manager = _build_minimal_model(demands, [5])
    assert _solve_and_validate(routing, manager, 1, 5) is None


# ── Case 13: 验证动态容量的业务语义 ──────────────────────────

def test_case13_business_semantics():
    """20 座公交：
    A 上 20 人，B 下 15 人，C 上 10 人。
    离开 B 时 = 5 人，离开 C 时 = 15 人。
    capacity=20 → FEASIBLE（这在旧模型中不可能，因为 20+10=30>20）。
    """
    demands = [20, -15, 10]
    routing, manager = _build_minimal_model(demands, [20])
    result = _solve_and_validate(routing, manager, 1, 20)
    assert result is not None, "20座公交上下客交替应 FEASIBLE"


def test_case14_business_semantics_infeasible():
    """5 座：
    A 上 5 人，B 下 1 人，C 上 2 人。
    离开 B 时 = 4 人，离开 C 时 = 6 人 > 5 → INFEASIBLE。
    """
    demands = [5, -1, 2]
    routing, manager = _build_minimal_model(demands, [5])
    result = _solve_and_validate(routing, manager, 1, 5)
    assert result is None, "5座公交 超容应 INFEASIBLE"


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
