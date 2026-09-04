"""HACO-CPS 1.4.1 Benchmark Scenarios.

S1: 5 passenger, 3 delivery, 2 vehicles
S2: 10 mixed tasks, 3 vehicles
S3: 25 mixed tasks, 5 vehicles
S4: 50 mixed tasks, 8 vehicles
S5: 25 tasks + skeleton, 5 vehicles

Each scenario runs BASELINE, HACO, HYBRID and records:
    status, vehicle_count, distance, passenger_impact, cargo_detour, duration, runtime_ms
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
from app.objective_compare import evaluate_solution_objective
from app.haco.route_genome import EventType, RouteEvent, RouteGenome
from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine

TZ = timezone(timedelta(hours=8))
DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId=f"S{i}", longitude=104.000 + i * 0.005, latitude=30.000 + i * 0.005)
    for i in range(1, 21)
]


def _make_request(orders, shipments=None, vehicles=None, mode=AlgorithmMode.HACO, **kw):
    if vehicles is None:
        vehicles = [Vehicle(vehicleId=1000 + i) for i in range(3)]
    cfg = AlgorithmConfig(algorithmMode=mode, **kw)
    return PlanRequest(
        requestId=f"bench-{mode.value}",
        batchStart=datetime(2026, 8, 23, 8, 0, tzinfo=TZ),
        batchEnd=datetime(2026, 8, 23, 18, 0, tzinfo=TZ),
        depot=DEPOT,
        stations=STATIONS,
        vehicles=vehicles,
        orders=orders,
        shipments=shipments or [],
        algorithmConfig=cfg,
    )


def _count_expected_tasks(req):
    """Count expected tasks from request orders + shipments."""
    count = 0
    for o in req.orders:
        count += 1
    for s in req.shipments:
        count += 1
    return count


def _count_planned_tasks(result):
    """Count unique task IDs in the result (not stop count)."""
    if result.status != "feasible":
        return 0
    task_ids = set()
    for p in result.vehicle_plans:
        for s in p.stops:
            if s.orderId and s.action not in (StopAction.DEPART, StopAction.RETURN):
                task_ids.add(s.orderId)
    return len(task_ids)


def _run_and_record(req, label):
    t0 = time.monotonic()
    result = unified_solve(req)
    elapsed = (time.monotonic() - t0) * 1000

    expected_tasks = _count_expected_tasks(req)
    planned_tasks = _count_planned_tasks(result)

    record = {
        "label": label,
        "status": result.status,
        "algorithm_version": result.algorithm_version,
        "vehicle_count": len(result.vehicle_plans) if result.status == "feasible" else 0,
        "distance": result.total_distance if result.status == "feasible" else 0,
        "runtime_ms": round(elapsed, 1),
        "expected_tasks": expected_tasks,
        "planned_tasks": planned_tasks,
        "task_completeness": planned_tasks == expected_tasks,
        "warnings": result.warnings,
    }

    if result.status == "feasible" and result.vehicle_plans:
        obj = evaluate_solution_objective(result.vehicle_plans)
        record["passenger_impact"] = obj.passenger_impact
        record["cargo_detour"] = obj.cargo_detour
        record["duration"] = obj.total_duration
        record["vehicle_count"] = obj.vehicle_count
        record["objective_key"] = obj.key()

    return record


def _print_record(record):
    """Print detailed benchmark record."""
    print(f"\n  {record['label']}:")
    print(f"    status={record['status']}, version={record['algorithm_version']}")
    print(f"    runtime_ms={record['runtime_ms']}")
    if record['status'] == 'feasible':
        print(f"    vehicles={record['vehicle_count']}, distance={record['distance']:.3f}")
        print(f"    pax_impact={record.get('passenger_impact', 0):.1f}, cargo_detour={record.get('cargo_detour', 0):.3f}")
        print(f"    duration={record.get('duration', 0):.1f}")
    print(f"    tasks: expected={record['expected_tasks']}, planned={record['planned_tasks']}, complete={record['task_completeness']}")
    if record.get('objective_key'):
        print(f"    objective_key={record['objective_key']}")


# S1: 5 passenger + 3 delivery, 2 vehicles


def _s1_orders():
    orders = []
    for i in range(5):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{i+1}", alightingStationId=f"S{i+6}",
        ))
    for i in range(3):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i+11}", itemCount=1,
        ))
    return orders


def _s1_vehicles():
    return [Vehicle(vehicleId=1000 + i, passengerCapacity=4, cargoCapacity=10) for i in range(2)]


# S2: 10 mixed tasks, 3 vehicles


def _s2_orders():
    orders = []
    for i in range(4):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{i+1}", alightingStationId=f"S{i+5}",
        ))
    for i in range(3):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i+9}", itemCount=1,
        ))
    for i in range(3):
        orders.append(PlanOrder(
            orderId=f"K{i}", orderType=OrderType.PICKUP,
            stationId=f"S{i+12}", itemCount=1,
        ))
    return orders


def _s2_vehicles():
    return [Vehicle(vehicleId=1000 + i, passengerCapacity=4, cargoCapacity=10) for i in range(3)]


# S3: 25 mixed tasks, 5 vehicles


def _s3_orders():
    orders = []
    for i in range(10):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{i % 15 + 1}", alightingStationId=f"S{(i + 5) % 15 + 1}",
        ))
    for i in range(8):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i % 15 + 1}", itemCount=1,
        ))
    for i in range(7):
        orders.append(PlanOrder(
            orderId=f"K{i}", orderType=OrderType.PICKUP,
            stationId=f"S{i % 15 + 1}", itemCount=1,
        ))
    return orders


def _s3_vehicles():
    return [Vehicle(vehicleId=1000 + i, passengerCapacity=6, cargoCapacity=15) for i in range(5)]


# S4: 50 mixed tasks, 8 vehicles


def _s4_orders():
    orders = []
    for i in range(20):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{i % 18 + 1}", alightingStationId=f"S{(i + 7) % 18 + 1}",
        ))
    for i in range(15):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i % 18 + 1}", itemCount=1,
        ))
    for i in range(15):
        orders.append(PlanOrder(
            orderId=f"K{i}", orderType=OrderType.PICKUP,
            stationId=f"S{i % 18 + 1}", itemCount=1,
        ))
    return orders


def _s4_vehicles():
    return [Vehicle(vehicleId=1000 + i, passengerCapacity=8, cargoCapacity=20) for i in range(8)]


# S5: 25 tasks + skeleton, 5 vehicles


def _s5_orders():
    return _s3_orders()


def _s5_vehicles():
    return [
        Vehicle(
            vehicleId=1000 + i,
            passengerCapacity=6,
            cargoCapacity=15,
            skeleton=[f"S{(i * 3 + j) % 18 + 1}" for j in range(1, 4)],
        )
        for i in range(5)
    ]


# Benchmark tests (slow-marked)


@pytest.mark.slow
class TestBenchmarkS1:
    def test_s1_baseline(self):
        req = _make_request(_s1_orders(), vehicles=_s1_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S1-BASELINE")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")

    def test_s1_haco(self):
        for i in range(3):
            req = _make_request(_s1_orders(), vehicles=_s1_vehicles(), mode=AlgorithmMode.HACO,
                                haco_time_limit=3.0, overall_time_limit=4.0)
            r = _run_and_record(req, f"S1-HACO-run{i}")
            _print_record(r)
            assert r["status"] in ("feasible", "infeasible")
            assert r["runtime_ms"] < 5000

    def test_s1_hybrid(self):
        req = _make_request(_s1_orders(), vehicles=_s1_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=3.0, overall_time_limit=4.0)
        r = _run_and_record(req, "S1-HYBRID")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS2:
    def test_s2_baseline(self):
        req = _make_request(_s2_orders(), vehicles=_s2_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S2-BASELINE")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")

    def test_s2_haco(self):
        for i in range(3):
            req = _make_request(_s2_orders(), vehicles=_s2_vehicles(), mode=AlgorithmMode.HACO,
                                haco_time_limit=3.0, overall_time_limit=4.0)
            r = _run_and_record(req, f"S2-HACO-run{i}")
            _print_record(r)
            assert r["status"] in ("feasible", "infeasible")
            assert r["runtime_ms"] < 5000

    def test_s2_hybrid(self):
        req = _make_request(_s2_orders(), vehicles=_s2_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=3.0, overall_time_limit=4.0)
        r = _run_and_record(req, "S2-HYBRID")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS3:
    def test_s3_baseline(self):
        req = _make_request(_s3_orders(), vehicles=_s3_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S3-BASELINE")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")

    def test_s3_haco(self):
        for i in range(3):
            req = _make_request(_s3_orders(), vehicles=_s3_vehicles(), mode=AlgorithmMode.HACO,
                                haco_time_limit=4.0, overall_time_limit=5.0)
            r = _run_and_record(req, f"S3-HACO-run{i}")
            _print_record(r)
            assert r["status"] in ("feasible", "infeasible")
            assert r["runtime_ms"] < 7000

    def test_s3_hybrid(self):
        req = _make_request(_s3_orders(), vehicles=_s3_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S3-HYBRID")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS4:
    def test_s4_baseline(self):
        req = _make_request(_s4_orders(), vehicles=_s4_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S4-BASELINE")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")

    def test_s4_haco(self):
        for i in range(3):
            req = _make_request(_s4_orders(), vehicles=_s4_vehicles(), mode=AlgorithmMode.HACO,
                                haco_time_limit=4.0, overall_time_limit=5.0)
            r = _run_and_record(req, f"S4-HACO-run{i}")
            _print_record(r)
            assert r["status"] in ("feasible", "infeasible")
            assert r["runtime_ms"] < 7000

    def test_s4_hybrid(self):
        req = _make_request(_s4_orders(), vehicles=_s4_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S4-HYBRID")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS5:
    def test_s5_baseline(self):
        req = _make_request(_s5_orders(), vehicles=_s5_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S5-BASELINE")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")

    def test_s5_haco(self):
        for i in range(3):
            req = _make_request(_s5_orders(), vehicles=_s5_vehicles(), mode=AlgorithmMode.HACO,
                                haco_time_limit=4.0, overall_time_limit=5.0)
            r = _run_and_record(req, f"S5-HACO-run{i}")
            _print_record(r)
            assert r["status"] in ("feasible", "infeasible")
            assert r["runtime_ms"] < 7000

    def test_s5_hybrid(self):
        req = _make_request(_s5_orders(), vehicles=_s5_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S5-HYBRID")
        _print_record(r)
        assert r["status"] in ("feasible", "infeasible")


# Pruning performance metrics


@pytest.mark.slow
class TestPruningPerformance:
    def test_pruning_reduces_evaluations(self):
        """Compare exhaustive vs pruned candidate generation counts."""
        from app.haco.construction import generate_insertion_candidates
        from tests.test_haco_141_regression import _generate_insertion_candidates_exhaustive

        route = RouteGenome(0, 1000, "S0")
        for s in ["S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8"]:
            route.events.insert(len(route.events) - 1,
                                RouteEvent(station_id=s, event_type=EventType.PASS))

        task = TaskBlock(task_id="T1", task_type=TaskType.PASSENGER,
                         pickup_station="S2", delivery_station="S3", size=1, order_ids=["T1"])
        tasks_by_id = {"T1": task}
        station_map = {s.stationId: s for s in STATIONS}
        station_map["S0"] = DEPOT
        engine = FeasibilityEngine(station_map=station_map, matrix=None)

        # Exhaustive
        t0 = time.perf_counter()
        exhaustive = _generate_insertion_candidates_exhaustive(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
        )
        exhaustive_ms = (time.perf_counter() - t0) * 1000

        # Pruned
        t0 = time.perf_counter()
        pruned = generate_insertion_candidates(
            task, [route], tasks_by_id, engine, station_map, None,
            {0: 10}, {0: 10}, {0: 0}, {0: 0},
            candidate_size=4,
        )
        pruned_ms = (time.perf_counter() - t0) * 1000

        # Total possible positions for a paired task: sum over pickup_index of (event_count - pickup_index)
        event_count = len(route.events)
        total_slots = sum(event_count - p for p in range(1, event_count))

        print(f"\n  Pruning Performance:")
        print(f"    Total candidate slots: {total_slots}")
        print(f"    Exhaustive full evaluations: {len(exhaustive)}")
        print(f"    Pruned full evaluations: {len(pruned)}")
        reduction = (1 - len(pruned) / max(len(exhaustive), 1)) * 100
        print(f"    Reduction: {reduction:.0f}%")
        print(f"    Exhaustive runtime: {exhaustive_ms:.1f}ms")
        print(f"    Pruned runtime: {pruned_ms:.1f}ms")
        if pruned_ms > 0:
            speedup = exhaustive_ms / pruned_ms
            print(f"    Speedup: {speedup:.1f}x")

        # Pruned should have fewer full evaluations
        assert len(pruned) < len(exhaustive), (
            f"Pruning didn't reduce evaluations: {len(pruned)} >= {len(exhaustive)}"
        )
