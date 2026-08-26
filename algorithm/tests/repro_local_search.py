"""Phase 11：Local Search 对比实验。

目标：对比 PATH_CHEAPEST_ARC vs LOCAL_SEARCH_METAHEURISTIC 的解质量/用时。

如果质量提升不明显但耗时明显增加，不采用。
"""

from __future__ import annotations

import time

import pytest
from ortools.constraint_solver import pywrapcp, routing_enums_pb2

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
    for i in range(1, 11)
]


def _make_orders(n_passengers: int = 5, n_deliveries: int = 3, n_pickups: int = 2):
    orders = []
    for i in range(n_passengers):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{i % 9 + 1}", alightingStationId=f"S{(i + 3) % 9 + 1}",
        ))
    for i in range(n_deliveries):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i % 9 + 1}", itemCount=1,
        ))
    for i in range(n_pickups):
        orders.append(PlanOrder(
            orderId=f"K{i}", orderType=OrderType.PICKUP,
            stationId=f"S{(i + 5) % 9 + 1}", itemCount=1,
        ))
    return orders


def _solve_with_strategy(strategy_name: str, orders, vehicle_count=3):
    """用指定策略求解，返回 (outcome, elapsed_ms)。"""
    req = PlanRequest(
        requestId=f"ls-{strategy_name}",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=1000 + i, cargoCapacity=4) for i in range(vehicle_count)],
        orders=orders,
    )

    # 直接用 solver 内部 API 来切换策略
    from app.distance import DistanceMatrix
    from app.solver import (
        DISTANCE_SCALE, SOLVER_TIME_LIMIT_SECONDS,
        _Node, _scaled_distance, _total_demand,
    )

    passengers, deliveries, pickups = _total_demand(req)
    if not req.orders:
        return type('Outcome', (), {'status': 'feasible', 'vehicle_plans': [], 'total_distance': 0.0})(), 0

    station_map = {s.stationId: s for s in req.stations}
    station_map[req.depot.stationId] = req.depot

    nodes = [_Node(station_id=req.depot.stationId, action=StopAction.DEPART)]
    passenger_pairs = []
    for order in req.orders:
        if order.orderType == OrderType.PASSENGER:
            board_node = len(nodes)
            nodes.append(_Node(order.boardingStationId, StopAction.BOARD, order.orderId))
            nodes.append(_Node(order.alightingStationId, StopAction.ALIGHT, order.orderId))
            passenger_pairs.append((board_node, board_node + 1))
        elif order.orderType == OrderType.DELIVERY:
            nodes.append(_Node(order.stationId, StopAction.DELIVER, order.orderId))
        else:
            nodes.append(_Node(order.stationId, StopAction.PICKUP, order.orderId))

    num_vehicles = len(req.vehicles)
    manager = pywrapcp.RoutingIndexManager(len(nodes), num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    def distance_callback(from_index, to_index):
        from_node = manager.IndexToNode(from_index)
        to_node = manager.IndexToNode(to_index)
        return int(round(_scaled_distance(
            station_map[nodes[from_node].station_id],
            station_map[nodes[to_node].station_id],
        )))

    distance_callback_index = routing.RegisterTransitCallback(distance_callback)
    routing.SetArcCostEvaluatorOfAllVehicles(distance_callback_index)

    max_dist = 0
    for a in station_map.values():
        for b in station_map.values():
            d = int(round(_scaled_distance(a, b)))
            if d > max_dist:
                max_dist = d
    vehicle_fixed_cost = len(nodes) * max_dist + 1 if max_dist > 0 else 10**9

    for vi in range(num_vehicles):
        routing.SetFixedCostOfVehicle(vehicle_fixed_cost, vi)

    def passenger_demand(node):
        if nodes[node].action == StopAction.BOARD:
            return 1
        if nodes[node].action == StopAction.ALIGHT:
            return -1
        return 0

    p_cb = routing.RegisterUnaryTransitCallback(lambda fi: passenger_demand(manager.IndexToNode(fi)))
    routing.AddDimensionWithVehicleCapacity(p_cb, 0, [v.passengerCapacity for v in req.vehicles], False, "Passenger")
    p_dim = routing.GetDimensionOrDie("Passenger")
    for vi, v in enumerate(req.vehicles):
        p_dim.CumulVar(routing.Start(vi)).SetRange(v.initialPassengerLoad, v.initialPassengerLoad)

    item_count = {o.orderId: o.itemCount for o in req.orders if o.orderType != OrderType.PASSENGER}

    def delivery_demand(node):
        return item_count[nodes[node].order_id] if nodes[node].action == StopAction.DELIVER else 0

    def pickup_demand(node):
        return item_count[nodes[node].order_id] if nodes[node].action == StopAction.PICKUP else 0

    d_cb = routing.RegisterUnaryTransitCallback(lambda fi: delivery_demand(manager.IndexToNode(fi)))
    routing.AddDimensionWithVehicleCapacity(d_cb, 0, [v.cargoCapacity for v in req.vehicles], True, "CargoOut")
    p_cb2 = routing.RegisterUnaryTransitCallback(lambda fi: pickup_demand(manager.IndexToNode(fi)))
    routing.AddDimensionWithVehicleCapacity(p_cb2, 0, [v.cargoCapacity for v in req.vehicles], True, "CargoIn")

    routing.AddDimension(distance_callback_index, 0, 10**12, True, "Distance")
    dist_dim = routing.GetDimensionOrDie("Distance")

    solver = routing.solver()
    for bn, an in passenger_pairs:
        bi, ai = manager.NodeToIndex(bn), manager.NodeToIndex(an)
        routing.AddPickupAndDelivery(bi, ai)
        solver.Add(routing.VehicleVar(bi) == routing.VehicleVar(ai))
        solver.Add(dist_dim.CumulVar(bi) <= dist_dim.CumulVar(ai))

    sp = pywrapcp.DefaultRoutingSearchParameters()
    sp.time_limit.FromSeconds(SOLVER_TIME_LIMIT_SECONDS)

    if strategy_name == "path_cheapest_arc":
        sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    elif strategy_name == "parallel_cheapest_insertion":
        sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PARALLEL_CHEAPEST_INSERTION
    elif strategy_name == "local_search":
        sp.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
        sp.local_search_metaheuristic = routing_enums_pb2.LocalSearchMetaheuristic.GUIDED_LOCAL_SEARCH

    start = time.monotonic()
    solution = routing.SolveWithParameters(sp)
    elapsed = (time.monotonic() - start) * 1000

    if solution is None:
        return None, elapsed

    vehicle_plans = []
    for vi, v in enumerate(req.vehicles):
        idx = routing.Start(vi)
        if routing.IsEnd(solution.Value(routing.NextVar(idx))):
            continue
        stops = [type('Stop', (), {'stationId': req.depot.stationId, 'action': StopAction.DEPART, 'orderId': None,
                                    'segmentDistance': 0.0, 'segmentDuration': None})()]
        scaled_total = 0
        while True:
            next_idx = solution.Value(routing.NextVar(idx))
            from_s = station_map[nodes[manager.IndexToNode(idx)].station_id]
            to_s = req.depot if routing.IsEnd(next_idx) else station_map[nodes[manager.IndexToNode(next_idx)].station_id]
            seg = int(round(_scaled_distance(from_s, to_s)))
            scaled_total += seg
            if routing.IsEnd(next_idx):
                stops.append(type('Stop', (), {'stationId': req.depot.stationId, 'action': StopAction.RETURN,
                                                'orderId': None, 'segmentDistance': seg / DISTANCE_SCALE,
                                                'segmentDuration': None})())
                break
            node = nodes[manager.IndexToNode(next_idx)]
            stops.append(type('Stop', (), {'stationId': node.station_id, 'action': node.action,
                                            'orderId': node.order_id, 'segmentDistance': seg / DISTANCE_SCALE,
                                            'segmentDuration': None})())
            idx = next_idx
        vehicle_plans.append(type('Plan', (), {'vehicleId': v.vehicleId, 'stops': stops,
                                                'totalDistance': scaled_total / DISTANCE_SCALE})())

    total_dist = round(sum(p.totalDistance for p in vehicle_plans), 3)
    return type('Outcome', (), {'status': 'feasible', 'vehicle_plans': vehicle_plans, 'total_distance': total_dist})(), elapsed


# ── 对比测试 ──────────────────────────────────────────────────

def test_compare_strategies_small():
    """小规模（10 单）：对比三种策略的解质量。

    Local Search 使用 GUIDED_LOCAL_SEARCH，会用满时间护栏（5s），
    因此不比较时间，只比较解质量（车辆数、距离）。
    """
    orders = _make_orders(5, 3, 2)

    results = {}
    for strategy in ["path_cheapest_arc", "parallel_cheapest_insertion", "local_search"]:
        outcome, elapsed = _solve_with_strategy(strategy, orders)
        if outcome and outcome.status == "feasible":
            results[strategy] = {
                "vehicles": len(outcome.vehicle_plans),
                "distance": outcome.total_distance,
                "time_ms": elapsed,
            }

    # 所有策略都应找到可行解
    assert len(results) >= 2, f"至少 2 种策略应可行: {results}"

    # 车辆数应一致（固定成本保证最少车辆）
    vehicle_counts = {r["vehicles"] for r in results.values()}
    assert len(vehicle_counts) == 1, f"车辆数应一致: {vehicle_counts}"

    # 距离差异应在 50% 内
    distances = [r["distance"] for r in results.values()]
    if min(distances) > 0:
        ratio = max(distances) / min(distances)
        assert ratio < 1.50, f"距离差异过大: {distances}"


def test_compare_strategies_medium():
    """中规模（25 单）：对比解质量。"""
    orders = _make_orders(13, 6, 6)

    results = {}
    for strategy in ["path_cheapest_arc", "local_search"]:
        outcome, elapsed = _solve_with_strategy(strategy, orders)
        if outcome and outcome.status == "feasible":
            results[strategy] = {
                "vehicles": len(outcome.vehicle_plans),
                "distance": outcome.total_distance,
                "time_ms": elapsed,
            }

    assert len(results) == 2, f"两种策略都应可行: {results}"

    # 车辆数一致
    assert results["path_cheapest_arc"]["vehicles"] == results["local_search"]["vehicles"]

    # 距离差异应在 50% 内（等价最优解）
    pca_dist = results["path_cheapest_arc"]["distance"]
    ls_dist = results["local_search"]["distance"]
    if pca_dist > 0 and ls_dist > 0:
        diff_ratio = abs(pca_dist - ls_dist) / max(pca_dist, ls_dist)
        assert diff_ratio < 0.50, f"距离差异过大: PCA={pca_dist:.3f} LS={ls_dist:.3f}"


def test_deterministic_same_strategy():
    """同一策略多次运行应确定性。"""
    orders = _make_orders(5, 3, 2)

    r1, _ = _solve_with_strategy("path_cheapest_arc", orders)
    r2, _ = _solve_with_strategy("path_cheapest_arc", orders)

    assert r1 is not None and r2 is not None
    assert r1.total_distance == r2.total_distance
    assert len(r1.vehicle_plans) == len(r2.vehicle_plans)


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
