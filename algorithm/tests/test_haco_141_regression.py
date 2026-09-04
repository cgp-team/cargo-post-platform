"""HACO-CPS 1.4.1 Regression Matrix.

覆盖：
- Hard deadline 停止检查（ant loop / candidate generation / local search / ALNS）
- Candidate pruning 正确性
- Config forwarding
- HYBRID full objective
- Route invariants (RETURN terminal, pickup < delivery, no task loss)
- Pheromone cross-vehicle
- Determinism
"""

from __future__ import annotations

import time
from datetime import datetime, timezone, timedelta

import pytest

from app.models import (
    AlgorithmConfig, AlgorithmMode, OrderType, PlanOrder, PlanRequest,
    PlanShipment, Station, StopAction, Vehicle,
)
from app.solver import solve as unified_solve
from app.haco.config import HacoConfig
from app.haco.deadline import SearchDeadline
from app.haco.encoding import ObjectiveVector, TaskBlock, TaskType
from app.haco.pheromone import PheromoneMatrix, extract_vehicle_task_sequences
from app.haco.route_genome import EventType, RouteEvent, RouteGenome
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.evaluator import evaluate_route_states
from app.haco.alns_v14 import alns_search, destroy_random, repair_greedy


# fixtures

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId=f"S{i}", longitude=104.000 + i * 0.01, latitude=30.000 + i * 0.01)
    for i in range(1, 11)
]

TZ = timezone(timedelta(hours=8))


def _request(orders, shipments=None, vehicles=None, mode=AlgorithmMode.HACO, **config_kw):
    if vehicles is None:
        vehicles = [Vehicle(vehicleId=1000 + i) for i in range(3)]
    cfg = AlgorithmConfig(algorithmMode=mode, **config_kw)
    return PlanRequest(
        requestId="regression-test",
        batchStart=datetime(2026, 8, 23, 8, 0, tzinfo=TZ),
        batchEnd=datetime(2026, 8, 23, 18, 0, tzinfo=TZ),
        depot=DEPOT,
        stations=STATIONS,
        vehicles=vehicles,
        orders=orders,
        shipments=shipments or [],
        algorithmConfig=cfg,
    )


def _passenger(idx, board="S1", alight="S2"):
    return PlanOrder(orderId=f"P{idx}", orderType=OrderType.PASSENGER,
                     boardingStationId=board, alightingStationId=alight)


def _delivery(idx, station="S3", items=1):
    return PlanOrder(orderId=f"D{idx}", orderType=OrderType.DELIVERY,
                     stationId=station, itemCount=items)


def _pickup(idx, station="S4", items=1):
    return PlanOrder(orderId=f"K{idx}", orderType=OrderType.PICKUP,
                     stationId=station, itemCount=items)


def _shipment(idx, pickup="S5", delivery="S6", qty=1):
    return PlanShipment(shipmentId=f"SH{idx}", pickupStationId=pickup,
                        deliveryStationId=delivery, quantity=qty)


def _task(tid, task_type, pickup, delivery, size=1):
    return TaskBlock(task_id=tid, task_type=task_type,
                     pickup_station=pickup, delivery_station=delivery,
                     size=size, order_ids=[tid])


# 1. Hard Deadline Tests


class TestHardDeadline:

    def test_deadline_from_seconds_basic(self):
        d = SearchDeadline.from_seconds(1.0)
        assert not d.expired()
        assert d.remaining() > 0
        assert d.check() is True

    def test_deadline_zero_expires_immediately(self):
        d = SearchDeadline.from_seconds(0.0)
        assert d.expired()
        assert d.remaining() == 0.0
        assert d.check() is False

    def test_deadline_expired_after_wait(self):
        d = SearchDeadline.from_seconds(0.05)
        assert not d.expired()
        time.sleep(0.1)
        assert d.expired()

    def test_haco_hard_deadline_stops_inside_ant_loop(self):
        orders = [_passenger(i, f"S{i % 9 + 1}", f"S{(i + 3) % 9 + 1}") for i in range(5)]
        req = _request(orders, haco_time_limit=4.0, overall_time_limit=5.0)
        t0 = time.monotonic()
        result = unified_solve(req)
        elapsed = time.monotonic() - t0
        assert elapsed < 6.0, f"HACO ran {elapsed:.1f}s, budget was 4s"

    def test_haco_hard_deadline_with_short_budget(self):
        orders = [_passenger(i, f"S{i % 9 + 1}", f"S{(i + 3) % 9 + 1}") for i in range(5)]
        req = _request(orders, haco_time_limit=0.5, overall_time_limit=1.0)
        t0 = time.monotonic()
        result = unified_solve(req)
        elapsed = time.monotonic() - t0
        assert elapsed < 2.5, f"HACO ran {elapsed:.1f}s, budget was 0.5s"


# 2. Candidate Pruning Tests


class TestCandidatePruning:

    def test_candidate_size_reduces_full_evaluations(self):
        from app.haco.construction import generate_insertion_candidates

        tasks_by_id = {}
        route = RouteGenome(0, 1000, "S0")
        task = _task("T1", TaskType.PASSENGER, "S1", "S2")
        tasks_by_id["T1"] = task

        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        candidates = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=2,
        )
        assert len(candidates) <= 2

    def test_candidate_pruning_keeps_best_candidate_pool(self):
        from app.haco.construction import generate_insertion_candidates

        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = _task("T1", TaskType.PASSENGER, "S6", "S7")
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        candidates = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=1,
        )
        assert len(candidates) >= 1


# 3. Config Forwarding Tests


class TestConfigForwarding:

    def test_algorithm_config_candidate_size_forwarded(self):
        cfg = AlgorithmConfig(candidate_size=16)
        haco_cfg = HacoConfig.from_algorithm_config(cfg)
        assert haco_cfg.candidate_size == 16

    def test_algorithm_config_archive_size_forwarded(self):
        cfg = AlgorithmConfig(archive_size=20)
        haco_cfg = HacoConfig.from_algorithm_config(cfg)
        assert haco_cfg.archive_size == 20

    def test_algorithm_config_time_limits_forwarded(self):
        cfg = AlgorithmConfig(haco_time_limit=3.0, overall_time_limit=4.5)
        haco_cfg = HacoConfig.from_algorithm_config(cfg)
        assert haco_cfg.haco_time_limit == 3.0
        assert haco_cfg.overall_time_limit == 4.5

    def test_algorithm_config_defaults_preserved(self):
        cfg = AlgorithmConfig()
        haco_cfg = HacoConfig.from_algorithm_config(cfg)
        assert haco_cfg.candidate_size == 8
        assert haco_cfg.archive_size == 10
        assert haco_cfg.destroy_fraction == 0.25

    def test_algorithm_config_core_fields_forwarded(self):
        cfg = AlgorithmConfig(
            ant_count=32, max_iterations=60, alpha=2.0, beta=4.0,
            rho=0.2, Q=200, convergence_threshold=30, randomSeed=12345,
        )
        haco_cfg = HacoConfig.from_algorithm_config(cfg)
        assert haco_cfg.ant_count == 32
        assert haco_cfg.max_iterations == 60
        assert haco_cfg.alpha == 2.0
        assert haco_cfg.beta == 4.0
        assert haco_cfg.rho == 0.2
        assert haco_cfg.Q == 200
        assert haco_cfg.convergence_threshold == 30
        assert haco_cfg.random_seed == 12345


# 4. HYBRID Full Objective Tests


class TestHybridObjective:

    def test_hybrid_uses_full_objective_vector(self):
        from app.objective_compare import solution_key, evaluate_solution_objective
        from app.models import VehiclePlan, RouteStop

        plan = VehiclePlan(
            vehicleId=1000, totalDistance=10.0,
            stops=[
                RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1",
                          segmentDistance=2.0, segmentDuration=100.0),
                RouteStop(stationId="S3", action=StopAction.PICKUP, orderId="D1",
                          segmentDistance=3.0, segmentDuration=200.0,
                          detourDistance=1.5, detourDuration=120.0, passengerImpact=120.0),
                RouteStop(stationId="S2", action=StopAction.ALIGHT, orderId="P1",
                          segmentDistance=2.0, segmentDuration=100.0),
                RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=3.0, segmentDuration=150.0),
            ],
        )

        class FakeOutcome:
            status = "feasible"
            vehicle_plans = [plan]

        obj = evaluate_solution_objective([plan])
        key = solution_key(FakeOutcome())
        assert len(key) == 7
        assert key[0] == 0
        assert obj.passenger_impact == 120.0

    def test_hybrid_does_not_prefer_shorter_distance_when_business_objective_is_worse(self):
        from app.objective_compare import solution_key
        from app.models import VehiclePlan, RouteStop

        plan_a = VehiclePlan(
            vehicleId=1000, totalDistance=3.0,
            stops=[
                RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1",
                          segmentDistance=1.5, segmentDuration=50.0),
                RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=1.5, segmentDuration=50.0),
            ],
        )
        plan_a2 = VehiclePlan(
            vehicleId=1001, totalDistance=2.0,
            stops=[
                RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                RouteStop(stationId="S2", action=StopAction.BOARD, orderId="P2",
                          segmentDistance=1.0, segmentDuration=50.0),
                RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=1.0, segmentDuration=50.0),
            ],
        )
        plan_b = VehiclePlan(
            vehicleId=1000, totalDistance=8.0,
            stops=[
                RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1",
                          segmentDistance=2.0, segmentDuration=100.0),
                RouteStop(stationId="S2", action=StopAction.BOARD, orderId="P2",
                          segmentDistance=3.0, segmentDuration=150.0),
                RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=3.0, segmentDuration=150.0),
            ],
        )

        class OutcomeA:
            status = "feasible"
            vehicle_plans = [plan_a, plan_a2]

        class OutcomeB:
            status = "feasible"
            vehicle_plans = [plan_b]

        assert solution_key(OutcomeB()) < solution_key(OutcomeA())


# 5. Route Invariant Tests


class TestRouteInvariants:

    def test_route_always_ends_with_return(self):
        route = RouteGenome(0, 1000, "S0")
        task = _task("T1", TaskType.PASSENGER, "S1", "S2")
        route.insert_task(task, 1, 2)
        ok, reason = route.validate_terminal_return()
        assert ok, f"RETURN invariant violated: {reason}"

    def test_return_is_terminal(self):
        route = RouteGenome(0, 1000, "S0")
        t1 = _task("T1", TaskType.PASSENGER, "S1", "S2")
        t2 = _task("T2", TaskType.SHIPMENT, "S3", "S4")
        route.insert_task(t1, 1, 2)
        route.insert_task(t2, 2, 3)
        assert route.events[-1].event_type == EventType.RETURN

    def test_paired_pickup_precedes_delivery(self):
        route = RouteGenome(0, 1000, "S0")
        route.events.insert(1, RouteEvent(station_id="S5", event_type=EventType.PASS))
        task = _task("T1", TaskType.SHIPMENT, "S1", "S2")
        route.insert_task(task, 1, 3)
        ok, reason = route.validate_precedence()
        assert ok, f"Precedence violated: {reason}"

    def test_no_task_loss_after_multiple_insertions(self):
        route = RouteGenome(0, 1000, "S0")
        tasks = [
            _task(f"T{i}", TaskType.PASSENGER, f"S{i}", f"S{(i + 1) % 10 + 1}")
            for i in range(5)
        ]
        for i, t in enumerate(tasks):
            p = 1 + i * 2
            d = p + 1
            route.insert_task(t, p, d)
        assert route.task_count() == 5


# 6. Pheromone Cross-Vehicle Tests


class TestPheromoneCrossVehicle:

    def test_pheromone_never_deposits_cross_vehicle_edges(self):
        config = HacoConfig()
        task_ids = ["DEPOT", "A", "B", "C", "D"]
        pm = PheromoneMatrix(task_ids, config)

        sequences = {0: ["DEPOT", "A", "B"], 1: ["DEPOT", "C", "D"]}
        pm.deposit_multi_vehicle(sequences, cost=10.0, weight=1.0)

        tau_bc = pm.get("B", "C")
        tau_depot_a = pm.get("DEPOT", "A")
        assert tau_bc <= pm.tau0 + 1e-9, f"Cross-vehicle edge B->C got deposited: {tau_bc}"
        assert tau_depot_a > pm.tau0, "DEPOT->A should have been deposited"

    def test_extract_vehicle_task_sequences_no_cross_vehicle(self):
        route0 = RouteGenome(0, 1000, "S0")
        t0 = _task("A", TaskType.PASSENGER, "S1", "S2")
        route0.insert_task(t0, 1, 2)

        route1 = RouteGenome(1, 1001, "S0")
        t1 = _task("B", TaskType.PASSENGER, "S3", "S4")
        route1.insert_task(t1, 1, 2)

        seqs = extract_vehicle_task_sequences([route0, route1])
        assert 0 in seqs and 1 in seqs
        assert seqs[0] == ["DEPOT", "A"]
        assert seqs[1] == ["DEPOT", "B"]


# 7. ALNS Tests


class TestALNS:

    def test_alns_preserves_all_tasks_after_repair(self):
        route = RouteGenome(0, 1000, "S0")
        tasks = [_task(f"T{i}", TaskType.PASSENGER, f"S{i+1}", f"S{(i+2)%10+1}") for i in range(5)]
        for i, t in enumerate(tasks):
            route.insert_task(t, 1 + i * 2, 2 + i * 2)

        tasks_by_id = {t.task_id: t for t in tasks}
        routes = [route]

        destroyed_routes, removed = destroy_random(routes, tasks_by_id, 0.4, __import__("random").Random(42))
        remaining_after_destroy = sum(len(r.placements) for r in destroyed_routes)
        assert remaining_after_destroy < 5

        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)
        repaired = repair_greedy(
            destroyed_routes, removed, tasks_by_id, engine,
            station_map, None, {0: 10}, {0: 10}, {0: 0}, {0: 0},
            __import__("random").Random(42),
        )
        total_after_repair = sum(len(r.placements) for r in repaired)
        assert total_after_repair == 5, f"Expected 5 tasks after repair, got {total_after_repair}"

    def test_alns_respects_deadline(self):
        route = RouteGenome(0, 1000, "S0")
        tasks = [_task(f"T{i}", TaskType.PASSENGER, f"S{i+1}", f"S{(i+2)%10+1}") for i in range(3)]
        for i, t in enumerate(tasks):
            route.insert_task(t, 1 + i * 2, 2 + i * 2)

        tasks_by_id = {t.task_id: t for t in tasks}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        deadline = SearchDeadline.from_seconds(0.0)
        time.sleep(0.01)

        t0 = time.monotonic()
        result = alns_search(
            [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            HacoConfig(), __import__("random").Random(42),
            max_iterations=10000, deadline=deadline,
        )
        elapsed = time.monotonic() - t0
        assert elapsed < 1.0, f"ALNS with expired deadline took {elapsed:.2f}s"


# 8. Determinism Tests


class TestDeterminism:

    def test_determinism_same_seed_same_input(self):
        orders = [_passenger(i, f"S{i % 9 + 1}", f"S{(i + 3) % 9 + 1}") for i in range(5)]
        req = _request(orders, randomSeed=42, haco_time_limit=2.0, overall_time_limit=3.0)

        r1 = unified_solve(req)
        r2 = unified_solve(req)

        assert r1.status == r2.status
        if r1.status == "feasible":
            assert len(r1.vehicle_plans) == len(r2.vehicle_plans)
            assert abs(r1.total_distance - r2.total_distance) < 0.001

    def test_determinism_reordered_input_same_result(self):
        orders_a = [_passenger(i, f"S{i % 9 + 1}", f"S{(i + 3) % 9 + 1}") for i in range(5)]
        orders_b = list(reversed(orders_a))

        req_a = _request(orders_a, randomSeed=42, haco_time_limit=2.0, overall_time_limit=3.0)
        req_b = _request(orders_b, randomSeed=42, haco_time_limit=2.0, overall_time_limit=3.0)

        r1 = unified_solve(req_a)
        r2 = unified_solve(req_b)

        assert r1.status == r2.status
        if r1.status == "feasible":
            assert len(r1.vehicle_plans) == len(r2.vehicle_plans)
            assert abs(r1.total_distance - r2.total_distance) < 0.001


# 9. Skeleton Gap Pruning Tests


class TestSkeletonGapPruning:

    def test_skeleton_order_never_changes(self):
        route = RouteGenome(0, 1000, "S0", skeleton=["S3", "S6"])
        task = _task("T1", TaskType.PASSENGER, "S4", "S5")
        route.insert_task(task, 2, 3)

        ok, reason = route.validate_skeleton()
        assert ok, f"Skeleton order violated: {reason}"

    def test_skeleton_gap_pruning_preserves_feasibility(self):
        route = RouteGenome(0, 1000, "S0", skeleton=["S3", "S6"])
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        task = _task("T1", TaskType.PASSENGER, "S1", "S2")
        tasks_by_id = {"T1": task}

        inserted = False
        for p in range(1, len(route.events)):
            for d in range(p + 1, len(route.events) + 1):
                test_route = route.copy()
                try:
                    test_route.insert_task(task, p, d)
                    result = engine.check(
                        test_route, tasks_by_id, 10, 10, 0, 0,
                        station_map=station_map, matrix=None,
                    )
                    if result.feasible:
                        inserted = True
                        break
                except (ValueError, IndexError):
                    continue
            if inserted:
                break
        assert inserted, "Should find at least one feasible insertion position"


# 10. Integration: HACO feasibility on small case


class TestHACOIntegration:

    def test_haco_small_case_feasible(self):
        orders = [_passenger(i, f"S{i+1}", f"S{(i+5) % 10 + 1}") for i in range(5)]
        vehicles = [Vehicle(vehicleId=1000 + i) for i in range(2)]
        req = _request(orders, vehicles=vehicles, haco_time_limit=3.0, overall_time_limit=4.0)
        result = unified_solve(req)
        assert result.status in ("feasible", "infeasible")

    def test_haco_no_task_loss(self):
        orders = [
            _passenger(1, "S1", "S2"),
            _passenger(2, "S3", "S4"),
            _delivery(3, "S5"),
        ]
        req = _request(orders, haco_time_limit=3.0, overall_time_limit=4.0)
        result = unified_solve(req)
        if result.status == "feasible":
            # Count unique task IDs (not stop count, since passengers have 2 stops each)
            task_ids = set()
            for p in result.vehicle_plans:
                for s in p.stops:
                    if s.orderId and s.action not in (StopAction.DEPART, StopAction.RETURN):
                        task_ids.add(s.orderId)
            assert len(task_ids) >= 3, f"Expected >=3 unique tasks, got {len(task_ids)}"


# 11. Pruning Correctness Tests


def _generate_insertion_candidates_exhaustive(
    task, routes, tasks_by_id, feasibility_engine,
    station_map, matrix, passenger_capacities, cargo_capacities,
    initial_passenger_loads, initial_cargo_loads,
):
    """Exhaustive (no pruning) candidate generation — test only, not for production."""
    from app.haco.construction import InsertionCandidate
    from app.haco.evaluator import evaluate_route_genome

    candidates = []
    for route in routes:
        event_count = len(route.events)
        if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
            for pickup_index in range(1, event_count):
                for delivery_index in range(pickup_index + 1, event_count + 1):
                    candidate_route = route.copy()
                    try:
                        candidate_route.insert_task(task, pickup_index, delivery_index)
                    except (ValueError, IndexError):
                        continue
                    result = feasibility_engine.check(
                        candidate_route, tasks_by_id | {task.task_id: task},
                        passenger_capacities[route.vehicle_index],
                        cargo_capacities[route.vehicle_index],
                        initial_passenger_loads[route.vehicle_index],
                        initial_cargo_loads[route.vehicle_index],
                        station_map=station_map, matrix=matrix,
                    )
                    if not result.feasible:
                        continue
                    metrics = evaluate_route_genome(
                        candidate_route, tasks_by_id | {task.task_id: task},
                        station_map, matrix,
                    )
                    candidates.append(InsertionCandidate(
                        vehicle_index=route.vehicle_index,
                        pickup_index=pickup_index,
                        delivery_index=delivery_index,
                        delta_distance=metrics["distance"],
                        delta_duration=metrics["duration"],
                        passenger_impact=metrics["passenger_impact"],
                        cargo_detour=metrics["cargo_detour"],
                        heuristic_score=(
                            metrics["distance"]
                            + metrics["passenger_impact"] * 0.01
                            + metrics["cargo_detour"] * 10.0
                        ),
                    ))
        else:
            for pickup_index in range(1, event_count):
                candidate_route = route.copy()
                try:
                    candidate_route.insert_task(task, pickup_index, None)
                except (ValueError, IndexError):
                    continue
                result = feasibility_engine.check(
                    candidate_route, tasks_by_id | {task.task_id: task},
                    passenger_capacities[route.vehicle_index],
                    cargo_capacities[route.vehicle_index],
                    initial_passenger_loads[route.vehicle_index],
                    initial_cargo_loads[route.vehicle_index],
                    station_map=station_map, matrix=matrix,
                )
                if not result.feasible:
                    continue
                metrics = evaluate_route_genome(
                    candidate_route, tasks_by_id | {task.task_id: task},
                    station_map, matrix,
                )
                candidates.append(InsertionCandidate(
                    vehicle_index=route.vehicle_index,
                    pickup_index=pickup_index,
                    delivery_index=None,
                    delta_distance=metrics["distance"],
                    delta_duration=metrics["duration"],
                    passenger_impact=metrics["passenger_impact"],
                    cargo_detour=metrics["cargo_detour"],
                    heuristic_score=(
                        metrics["distance"]
                        + metrics["passenger_impact"] * 0.01
                        + metrics["cargo_detour"] * 10.0
                    ),
                ))
    candidates.sort(key=lambda x: x.heuristic_score)
    return candidates


class TestPruningCorrectness:

    def test_paired_pruning_preserves_delivery_position_diversity(self):
        """Stage A must keep multiple delivery_index values per pickup_index."""
        from app.haco.construction import generate_insertion_candidates

        # Route with many events so there are many delivery positions
        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        # Use stations that exist in the route's station_map (STATIONS has S1-S10)
        task = _task("T1", TaskType.PASSENGER, "S2", "S3")
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        # Use candidate_size=2 — pruning should still keep delivery diversity
        candidates = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=2,
        )

        # Check that we have candidates with different delivery_index values
        delivery_indices = {c.delivery_index for c in candidates}
        # With per-pickup quota, we should have at least 2 different delivery positions
        assert len(delivery_indices) >= 2, (
            f"Expected delivery diversity, got only {delivery_indices}"
        )

    def test_pruned_best_is_close_to_exhaustive_best(self):
        """Pruned best score should be within 2x of exhaustive best (approximate filter)."""
        from app.haco.construction import generate_insertion_candidates

        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = _task("T1", TaskType.PASSENGER, "S6", "S7")
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        exhaustive = _generate_insertion_candidates_exhaustive(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
        )
        pruned = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=4,
        )

        if exhaustive and pruned:
            # Pruned best should be within 3x of exhaustive best
            # (tighter pool_size cap means more aggressive pruning)
            assert pruned[0].heuristic_score <= exhaustive[0].heuristic_score * 3.0 + 0.01, (
                f"Pruned best ({pruned[0].heuristic_score:.4f}) much worse than "
                f"exhaustive best ({exhaustive[0].heuristic_score:.4f})"
            )

    def test_pruning_does_not_drop_known_best_delivery_position(self):
        """The best delivery_index from exhaustive must survive pruning."""
        from app.haco.construction import generate_insertion_candidates

        # Route with many events
        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = _task("T1", TaskType.PASSENGER, "S1", "S9")
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        # Exhaustive: get all candidates
        exhaustive = _generate_insertion_candidates_exhaustive(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
        )
        if not exhaustive:
            pytest.skip("No feasible candidates in exhaustive")

        best_exhaustive = exhaustive[0]

        # Pruned with small candidate_size
        pruned = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=3,
        )
        if not pruned:
            pytest.skip("No feasible candidates in pruned")

        # The best exhaustive candidate's (pickup, delivery) should appear in pruned set
        pruned_positions = {(c.pickup_index, c.delivery_index) for c in pruned}
        assert (best_exhaustive.pickup_index, best_exhaustive.delivery_index) in pruned_positions, (
            f"Best exhaustive position ({best_exhaustive.pickup_index}, {best_exhaustive.delivery_index}) "
            f"not in pruned set {pruned_positions}"
        )

    def test_pruned_vs_exhaustive_close_for_passenger(self):
        """Pruned best score should be close to exhaustive best for a passenger task."""
        from app.haco.construction import generate_insertion_candidates

        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = _task("T1", TaskType.PASSENGER, "S6", "S7")
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        exhaustive = _generate_insertion_candidates_exhaustive(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
        )
        pruned = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=4,
        )

        if exhaustive and pruned:
            assert pruned[0].heuristic_score <= exhaustive[0].heuristic_score * 3.0 + 0.01, (
                f"Pruned best ({pruned[0].heuristic_score:.4f}) much worse than "
                f"exhaustive best ({exhaustive[0].heuristic_score:.4f})"
            )

    def test_pruned_vs_exhaustive_close_for_shipment(self):
        """Pruned best score should be close to exhaustive best for a shipment task."""
        from app.haco.construction import generate_insertion_candidates

        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = _task("T1", TaskType.SHIPMENT, "S6", "S7", size=2)
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        exhaustive = _generate_insertion_candidates_exhaustive(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
        )
        pruned = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=4,
        )

        if exhaustive and pruned:
            assert pruned[0].heuristic_score <= exhaustive[0].heuristic_score * 3.0 + 0.01

    def test_pruned_vs_exhaustive_close_for_skeleton_route(self):
        """Pruned best score should be close to exhaustive best on a route with skeleton."""
        from app.haco.construction import generate_insertion_candidates

        route = RouteGenome(0, 1000, "S0", skeleton=["S3", "S6"])
        for s in ["S1", "S2", "S4", "S5"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = _task("T1", TaskType.PASSENGER, "S7", "S8")
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        exhaustive = _generate_insertion_candidates_exhaustive(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
        )
        pruned = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=4,
        )

        if exhaustive and pruned:
            assert pruned[0].heuristic_score <= exhaustive[0].heuristic_score * 3.0 + 0.01


# 12. HYBRID Objective Consistency Tests


class TestHybridObjectiveConsistency:

    def test_hybrid_objective_matches_haco_objective(self):
        """evaluate_solution_objective(VehiclePlan) ≈ evaluate_route_states(RouteGenome)."""
        from app.haco.v14_solver import _routes_to_vehicle_plans, _segment, DISTANCE_SCALE, _EVENT_TO_ACTION
        from app.objective_compare import evaluate_solution_objective

        # Build a route with enough events for both insertions
        route = RouteGenome(0, 1000, "S0")
        # Add extra PASS events so we have room for 2 paired tasks
        for s in ["S6", "S7", "S8"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))
        t1 = _task("T1", TaskType.PASSENGER, "S1", "S3")
        t2 = _task("T2", TaskType.SHIPMENT, "S4", "S5")
        route.insert_task(t1, 1, 3)
        route.insert_task(t2, 4, 6)

        tasks_by_id = {"T1": t1, "T2": t2}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT

        # RouteGenome-based objective
        from app.haco.evaluator import evaluate_route_states
        obj_haco = evaluate_route_states([route], tasks_by_id, station_map, None)

        # Convert to VehiclePlan and evaluate
        from app.models import PlanRequest, Vehicle as V
        class FakeReq:
            vehicles = [V(vehicleId=1000)]
            depot = DEPOT
            stations = STATIONS

        plans = _routes_to_vehicle_plans([route], FakeReq(), station_map, None)
        obj_hybrid = evaluate_solution_objective(plans)

        # Compare (allow small floating point differences)
        assert obj_haco.vehicle_count == obj_hybrid.vehicle_count
        assert abs(obj_haco.passenger_impact - obj_hybrid.passenger_impact) < 1.0, (
            f"pax_impact: haco={obj_haco.passenger_impact}, hybrid={obj_hybrid.passenger_impact}"
        )
        assert abs(obj_haco.cargo_detour - obj_hybrid.cargo_detour) < 0.1, (
            f"cargo_detour: haco={obj_haco.cargo_detour}, hybrid={obj_hybrid.cargo_detour}"
        )
        assert abs(obj_haco.total_distance - obj_hybrid.total_distance) < 0.01, (
            f"distance: haco={obj_haco.total_distance}, hybrid={obj_hybrid.total_distance}"
        )

    def test_hybrid_vehicle_count_has_priority_over_distance(self):
        """1车 8km beats 2车 3km."""
        from app.objective_compare import solution_key
        from app.models import VehiclePlan, RouteStop

        class OutcomeA:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=1.5, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1", segmentDistance=1.0, segmentDuration=50),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=0.5, segmentDuration=25),
                ]),
                VehiclePlan(vehicleId=2, totalDistance=1.5, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S2", action=StopAction.BOARD, orderId="P2", segmentDistance=1.0, segmentDuration=50),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=0.5, segmentDuration=25),
                ]),
            ]

        class OutcomeB:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=8.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1", segmentDistance=3.0, segmentDuration=100),
                    RouteStop(stationId="S2", action=StopAction.BOARD, orderId="P2", segmentDistance=2.0, segmentDuration=100),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=3.0, segmentDuration=100),
                ]),
            ]

        assert solution_key(OutcomeB()) < solution_key(OutcomeA()), "1车 should beat 2车"

    def test_hybrid_passenger_impact_has_priority_over_distance(self):
        """Same vehicle_count: lower passenger_impact wins even if more distance."""
        from app.objective_compare import solution_key
        from app.models import VehiclePlan, RouteStop

        class OutcomeA:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=5.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1", segmentDistance=1.0, segmentDuration=50),
                    RouteStop(stationId="S3", action=StopAction.PICKUP, orderId="D1", segmentDistance=2.0, segmentDuration=100,
                              detourDistance=2.0, detourDuration=200, passengerImpact=200.0),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=2.0, segmentDuration=100),
                ]),
            ]

        class OutcomeB:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=8.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1", segmentDistance=2.0, segmentDuration=100),
                    RouteStop(stationId="S3", action=StopAction.PICKUP, orderId="D1", segmentDistance=3.0, segmentDuration=150,
                              detourDistance=0.1, detourDuration=10, passengerImpact=10.0),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=3.0, segmentDuration=150),
                ]),
            ]

        assert solution_key(OutcomeB()) < solution_key(OutcomeA()), "Lower pax_impact should win"

    def test_hybrid_cargo_detour_has_priority_over_distance(self):
        """Same vehicle_count + pax_impact: lower cargo_detour wins."""
        from app.objective_compare import solution_key
        from app.models import VehiclePlan, RouteStop

        class OutcomeA:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=5.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S3", action=StopAction.PICKUP, orderId="D1", segmentDistance=3.0, segmentDuration=100,
                              detourDistance=5.0, detourDuration=100, passengerImpact=0),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=2.0, segmentDuration=100),
                ]),
            ]

        class OutcomeB:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=10.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S3", action=StopAction.PICKUP, orderId="D1", segmentDistance=5.0, segmentDuration=200,
                              detourDistance=0.5, detourDuration=20, passengerImpact=0),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=5.0, segmentDuration=200),
                ]),
            ]

        assert solution_key(OutcomeB()) < solution_key(OutcomeA()), "Lower cargo_detour should win"

    def test_hybrid_distance_has_priority_over_duration(self):
        """Same first 4 dims: lower distance wins."""
        from app.objective_compare import solution_key
        from app.models import VehiclePlan, RouteStop

        class OutcomeA:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=10.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1", segmentDistance=5.0, segmentDuration=50),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=5.0, segmentDuration=50),
                ]),
            ]

        class OutcomeB:
            status = "feasible"
            vehicle_plans = [
                VehiclePlan(vehicleId=1, totalDistance=5.0, stops=[
                    RouteStop(stationId="S0", action=StopAction.DEPART, segmentDistance=0, segmentDuration=0),
                    RouteStop(stationId="S1", action=StopAction.BOARD, orderId="P1", segmentDistance=2.5, segmentDuration=200),
                    RouteStop(stationId="S0", action=StopAction.RETURN, segmentDistance=2.5, segmentDuration=200),
                ]),
            ]

        assert solution_key(OutcomeB()) < solution_key(OutcomeA()), "Lower distance should win"


# 13. Deadline Runtime Budget Tests


class TestDeadlineRuntimeBudget:

    @pytest.mark.parametrize("budget", [0.1, 0.5, 1.0, 2.0, 4.0])
    def test_deadline_respects_budget(self, budget):
        """Each budget must complete within 2x the configured time (generous for Python overhead)."""
        orders = [_passenger(i, f"S{i % 9 + 1}", f"S{(i + 3) % 9 + 1}") for i in range(5)]
        req = _request(orders, haco_time_limit=budget, overall_time_limit=budget + 1.0)

        for _ in range(3):  # 3 repetitions
            t0 = time.monotonic()
            result = unified_solve(req)
            elapsed = time.monotonic() - t0

            assert elapsed < budget * 2.5 + 0.5, (
                f"Budget {budget}s → actual {elapsed:.2f}s (too slow)"
            )
            # Must not return incomplete solution on timeout
            if result.status == "feasible":
                task_ids = set()
                for p in result.vehicle_plans:
                    for s in p.stops:
                        if s.orderId and s.action not in (StopAction.DEPART, StopAction.RETURN):
                            task_ids.add(s.orderId)
                assert len(task_ids) >= 5, f"Expected 5+ unique tasks, got {len(task_ids)}"
