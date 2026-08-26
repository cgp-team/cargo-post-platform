"""V2-4：CurrentCargoLoad 维度最小复现。

验证 PICKUP +quantity / DELIVERY -quantity 的正确行为。

CargoOut/CargoIn vs CurrentCargoLoad：
- CargoOut：出程派送能力（DELIVER +itemCount 累计，不减）
- CargoIn：返程揽收能力（PICKUP +itemCount 累计，不减）
- CurrentCargoLoad：车上真实货物量（PICKUP +, DELIVERY -）
"""

from __future__ import annotations

from ortools.constraint_solver import pywrapcp, routing_enums_pb2


def _build_cargo_load_model(
    demands: list[int],
    capacities: list[int],
    initial_loads: list[int],
) -> tuple[pywrapcp.RoutingModel, pywrapcp.RoutingIndexManager]:
    """构建带 CurrentCargoLoad 维度的最小模型。

    demands: 每个非 depot 节点的 demand（正值=PICKUP, 负值=DELIVERY）
    capacities: 每辆车的 cargoCapacity
    initial_loads: 每辆车的 initialCargoLoad
    """
    num_nodes = 1 + len(demands)
    num_vehicles = len(capacities)

    manager = pywrapcp.RoutingIndexManager(num_nodes, num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_demands = [0] + demands

    def demand_callback(from_index: int) -> int:
        return all_demands[manager.IndexToNode(from_index)]

    cb = routing.RegisterUnaryTransitCallback(demand_callback)

    # fix_start_cumul_to_0=False：支持 initialCargoLoad
    routing.AddDimensionWithVehicleCapacity(
        cb, 0, capacities, False, "CargoLoad",
    )

    dim = routing.GetDimensionOrDie("CargoLoad")

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

    dim = routing.GetDimensionOrDie("CargoLoad")
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


# ── CASE 1: initial=0, PICKUP 20, DELIVERY 20 → FEASIBLE ─────

def test_case1_pickup_delivery_balance():
    """initial=0, PICKUP +20, DELIVERY -20
    车上货物: 0 → 20 → 0
    capacity=30 → FEASIBLE
    """
    demands = [20, -20]
    capacities = [30]
    initial_loads = [0]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is not None, "应为 FEASIBLE"


# ── CASE 2: initial=50, PICKUP 20, DELIVERY 40 → FEASIBLE ────

def test_case2_initial_load_with_operations():
    """initial=50, PICKUP +20, DELIVERY -40
    车上货物: 50 → 70 → 30
    capacity=100 → FEASIBLE
    """
    demands = [20, -40]
    capacities = [100]
    initial_loads = [50]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is not None, "应为 FEASIBLE"


# ── CASE 3: initial=40, PICKUP 20, capacity=50 → INFEASIBLE ──

def test_case3_over_capacity():
    """initial=40, PICKUP +20 → 60 > 50 → INFEASIBLE"""
    demands = [20]
    capacities = [50]
    initial_loads = [40]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is None, "60 > 50 应 INFEASIBLE"


# ── CASE 4: initial=0, DELIVERY 20 → 需要区分语义 ─────────────

def test_case4_delivery_without_initial():
    """initial=0, DELIVERY -20 → 尝试到 -20，下界 0 阻止 → INFEASIBLE

    注意：这是 CurrentCargoLoad 维度的行为。
    CargoOut 维度允许 DELIVERY-before-PICKUP（出程派送语义）。
    两个维度语义不同。
    """
    demands = [-20]
    capacities = [50]
    initial_loads = [0]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is None, "DELIVERY 无初始货物应 INFEASIBLE（CargoLoad 维度）"


# ── CASE 5: 多个 shipment 交错 ────────────────────────────────

def test_case5_multiple_shipments_interleaved():
    """PICKUP +10, PICKUP +20, DELIVERY -15, DELIVERY -15
    车上货物: 0 → 10 → 30 → 15 → 0
    capacity=30 → FEASIBLE
    """
    demands = [10, 20, -15, -15]
    capacities = [30]
    initial_loads = [0]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is not None, "多 shipment 交错应 FEASIBLE"


# ── CASE 6: 容量边界 ──────────────────────────────────────────

def test_case6_exactly_at_capacity():
    """initial=0, PICKUP +50 → 50 = capacity → FEASIBLE"""
    demands = [50]
    capacities = [50]
    initial_loads = [0]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is not None


def test_case7_just_over_capacity():
    """initial=0, PICKUP +51 → 51 > 50 → INFEASIBLE"""
    demands = [51]
    capacities = [50]
    initial_loads = [0]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is None


# ── CASE 8: 负 demand 不能导致负 cumul ─────────────────────────

def test_case8_negative_cumul_blocked():
    """initial=10, DELIVERY -20 → 尝试到 -10，下界 0 阻止 → INFEASIBLE"""
    demands = [-20]
    capacities = [50]
    initial_loads = [10]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 1, capacities, initial_loads)
    assert result is None


# ── CASE 9: 多车独立容量 ──────────────────────────────────────

def test_case9_multi_vehicle_independent():
    """两车各 capacity=30, initial=0
    节点: PICKUP +20, PICKUP +20
    每车分一个 → 各 20 ≤ 30 → FEASIBLE
    """
    demands = [20, 20]
    capacities = [30, 30]
    initial_loads = [0, 0]
    routing, manager = _build_cargo_load_model(demands, capacities, initial_loads)
    result = _solve_and_validate(routing, manager, 2, capacities, initial_loads)
    # 可能 1 车或 2 车，取决于 Solver 分配
    assert result is not None


# ── CASE 10: PickDelivery 同车约束 ─────────────────────────────

def test_case10_pickup_delivery_same_vehicle():
    """PICKUP + DELIVERY 同车约束 + CargoLoad 维度。"""
    demands = [20, -20]
    capacities = [30]
    initial_loads = [0]

    manager = pywrapcp.RoutingIndexManager(3, 1, 0)
    routing = pywrapcp.RoutingModel(manager)

    all_demands = [0] + demands

    def demand_callback(from_index: int) -> int:
        return all_demands[manager.IndexToNode(from_index)]

    cb = routing.RegisterUnaryTransitCallback(demand_callback)
    routing.AddDimensionWithVehicleCapacity(cb, 0, capacities, False, "CargoLoad")

    dim = routing.GetDimensionOrDie("CargoLoad")
    dim.CumulVar(routing.Start(0)).SetRange(initial_loads[0], initial_loads[0])

    # AddPickupAndDelivery 约束
    pickup_idx = manager.NodeToIndex(1)
    delivery_idx = manager.NodeToIndex(2)
    routing.AddPickupAndDelivery(pickup_idx, delivery_idx)
    routing.solver().Add(routing.VehicleVar(pickup_idx) == routing.VehicleVar(delivery_idx))

    sp = pywrapcp.DefaultRoutingSearchParameters()
    sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    solution = routing.SolveWithParameters(sp)
    assert solution is not None, "PickupDelivery + CargoLoad 应 FEASIBLE"
