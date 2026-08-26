"""Phase 5：客货容量联合验证实验。

目标：验证 Passenger 和 Cargo 是独立限制，不能相加。

例如：
  Passenger capacity = 20
  Cargo capacity = 300

  不是：总容量 = 320
  而是：Passenger ≤ 20 且 Cargo ≤ 300

场景：
  A: BOARD 10, PICKUP 100kg
  B: ALIGHT 5, DELIVER 20kg
  C: BOARD 7

  Passenger: 10 → 5 → 12
  Cargo: 100 → 80 → 80
"""

from __future__ import annotations

import pytest
from ortools.constraint_solver import pywrapcp, routing_enums_pb2


def _build_joint_model(
    passenger_demands: list[int],
    cargo_out_demands: list[int],
    cargo_in_demands: list[int],
    passenger_capacities: list[int],
    cargo_capacities: list[int],
) -> tuple[pywrapcp.RoutingModel, pywrapcp.RoutingIndexManager]:
    """构建带 Passenger + CargoOut + CargoIn 三维度的模型。"""
    num_nodes = 1 + len(passenger_demands)
    num_vehicles = len(passenger_capacities)

    manager = pywrapcp.RoutingIndexManager(num_nodes, num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_passenger = [0] + passenger_demands
    all_cargo_out = [0] + cargo_out_demands
    all_cargo_in = [0] + cargo_in_demands

    def passenger_cb(from_index):
        return all_passenger[manager.IndexToNode(from_index)]

    def cargo_out_cb(from_index):
        return all_cargo_out[manager.IndexToNode(from_index)]

    def cargo_in_cb(from_index):
        return all_cargo_in[manager.IndexToNode(from_index)]

    p_idx = routing.RegisterUnaryTransitCallback(passenger_cb)
    co_idx = routing.RegisterUnaryTransitCallback(cargo_out_cb)
    ci_idx = routing.RegisterUnaryTransitCallback(cargo_in_cb)

    routing.AddDimensionWithVehicleCapacity(p_idx, 0, passenger_capacities, False, "Passenger")
    routing.AddDimensionWithVehicleCapacity(co_idx, 0, cargo_capacities, True, "CargoOut")
    routing.AddDimensionWithVehicleCapacity(ci_idx, 0, cargo_capacities, True, "CargoIn")

    # 设置初始乘客载荷为 0
    p_dim = routing.GetDimensionOrDie("Passenger")
    for v in range(num_vehicles):
        p_dim.CumulVar(routing.Start(v)).SetRange(0, 0)

    return routing, manager


def _solve(routing, manager, num_vehicles):
    sp = pywrapcp.DefaultRoutingSearchParameters()
    sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    return routing.SolveWithParameters(sp)


# ── Case 1: 客货独立容量 ──────────────────────────────────────

def test_case1_independent_capacities():
    """Passenger capacity=20, Cargo capacity=300
    A: BOARD 10, PICKUP 100
    B: ALIGHT 5, DELIVER 20
    C: BOARD 7

    Passenger peak: 10 → 5 → 12 ≤ 20 ✓
    CargoOut peak: 0 → 0 → 20 ≤ 300 ✓
    CargoIn peak: 0 → 100 → 100 ≤ 300 ✓
    → FEASIBLE
    """
    p_demands = [10, -5, 7]
    co_demands = [0, 20, 0]  # DELIVER at B
    ci_demands = [100, 0, 0]  # PICKUP at A

    routing, manager = _build_joint_model(
        p_demands, co_demands, ci_demands, [20], [300],
    )
    solution = _solve(routing, manager, 1)
    assert solution is not None, "客货独立容量应 FEASIBLE"


# ── Case 2: 乘客超容量 ────────────────────────────────────────

def test_case2_passenger_over_capacity():
    """Passenger capacity=5, Cargo capacity=300
    A: BOARD 10
    → Passenger 10 > 5 → INFEASIBLE
    """
    p_demands = [10]
    co_demands = [0]
    ci_demands = [0]

    routing, manager = _build_joint_model(
        p_demands, co_demands, ci_demands, [5], [300],
    )
    solution = _solve(routing, manager, 1)
    assert solution is None, "乘客超容量应 INFEASIBLE"


# ── Case 3: 货物超容量 ────────────────────────────────────────

def test_case3_cargo_over_capacity():
    """Passenger capacity=20, Cargo capacity=50
    A: PICKUP 100
    → CargoIn 100 > 50 → INFEASIBLE
    """
    p_demands = [0]
    co_demands = [0]
    ci_demands = [100]

    routing, manager = _build_joint_model(
        p_demands, co_demands, ci_demands, [20], [50],
    )
    solution = _solve(routing, manager, 1)
    assert solution is None, "货物超容量应 INFEASIBLE"


# ── Case 4: 客货都不超但总和超 ────────────────────────────────

def test_case4_sum_exceeds_but_independent_ok():
    """Passenger capacity=10, Cargo capacity=100
    A: BOARD 8, PICKUP 80

    Passenger 8 ≤ 10 ✓
    CargoIn 80 ≤ 100 ✓
    总和 8+80=88 < 10+100=110，但即使总和 > 110 也不影响。
    → FEASIBLE（独立检查）
    """
    p_demands = [8]
    co_demands = [0]
    ci_demands = [80]

    routing, manager = _build_joint_model(
        p_demands, co_demands, ci_demands, [10], [100],
    )
    solution = _solve(routing, manager, 1)
    assert solution is not None, "客货独立检查应 FEASIBLE"


# ── Case 5: 客货同时接近上限 ──────────────────────────────────

def test_case5_both_near_limit():
    """Passenger capacity=5, Cargo capacity=100
    A: BOARD 5, PICKUP 100
    → Passenger 5 ≤ 5 ✓, CargoIn 100 ≤ 100 ✓ → FEASIBLE

    A: BOARD 5, PICKUP 101
    → CargoIn 101 > 100 → INFEASIBLE
    """
    # 正好等于容量
    routing, manager = _build_joint_model([5], [0], [100], [5], [100])
    assert _solve(routing, manager, 1) is not None

    # 货物超容量
    routing, manager = _build_joint_model([5], [0], [101], [5], [100])
    assert _solve(routing, manager, 1) is None


# ── Case 6: 多车辆独立容量 ────────────────────────────────────

def test_case6_multi_vehicle_independent():
    """Vehicle 0: passenger=5, cargo=100
    Vehicle 1: passenger=10, cargo=50

    A: BOARD 6 → 分到 Vehicle 1 (passenger 10)
    B: PICKUP 60 → 分到 Vehicle 0 (cargo 100)
    """
    p_demands = [6, 0]
    co_demands = [0, 0]
    ci_demands = [0, 60]

    routing, manager = _build_joint_model(
        p_demands, co_demands, ci_demands,
        [5, 10], [100, 50],
    )
    solution = _solve(routing, manager, 2)
    assert solution is not None, "多车独立容量应 FEASIBLE"


# ── Case 7: 验证维度独立性 ────────────────────────────────────

def test_case7_dimensions_independent():
    """验证 Passenger、CargoOut、CargoIn 三个维度完全独立。

    场景：Passenger=5, Cargo=100
    A: BOARD 3, DELIVER 50
    B: BOARD 3, PICKUP 80

    Passenger: 3 → 6 > 5 → INFEASIBLE（乘客超）
    即使 CargoOut=50, CargoIn=80 都 ≤ 100
    """
    p_demands = [3, 3]
    co_demands = [50, 0]
    ci_demands = [0, 80]

    routing, manager = _build_joint_model(
        p_demands, co_demands, ci_demands, [5], [100],
    )
    solution = _solve(routing, manager, 1)
    assert solution is None, "乘客超容量应 INFEASIBLE（即使货物不超）"


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
