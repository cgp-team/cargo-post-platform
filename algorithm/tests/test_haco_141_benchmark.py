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
    PlanShipment, Station, Vehicle,
)
from app.solver import solve as unified_solve
from app.objective_compare import evaluate_solution_objective

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


def _run_and_record(req, label):
    t0 = time.monotonic()
    result = unified_solve(req)
    elapsed = (time.monotonic() - t0) * 1000

    record = {
        "label": label,
        "status": result.status,
        "algorithm_version": result.algorithm_version,
        "vehicle_count": len(result.vehicle_plans) if result.status == "feasible" else 0,
        "distance": result.total_distance if result.status == "feasible" else 0,
        "runtime_ms": round(elapsed, 1),
        "warnings": result.warnings,
    }

    if result.status == "feasible" and result.vehicle_plans:
        obj = evaluate_solution_objective(result.vehicle_plans)
        record["passenger_impact"] = obj.passenger_impact
        record["cargo_detour"] = obj.cargo_detour
        record["duration"] = obj.total_duration
        record["vehicle_count"] = obj.vehicle_count

    return record


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
        assert r["status"] in ("feasible", "infeasible")

    def test_s1_haco(self):
        req = _make_request(_s1_orders(), vehicles=_s1_vehicles(), mode=AlgorithmMode.HACO,
                            haco_time_limit=3.0, overall_time_limit=4.0)
        r = _run_and_record(req, "S1-HACO")
        assert r["status"] in ("feasible", "infeasible")
        assert r["runtime_ms"] < 5000

    def test_s1_hybrid(self):
        req = _make_request(_s1_orders(), vehicles=_s1_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=3.0, overall_time_limit=4.0)
        r = _run_and_record(req, "S1-HYBRID")
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS2:
    def test_s2_baseline(self):
        req = _make_request(_s2_orders(), vehicles=_s2_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S2-BASELINE")
        assert r["status"] in ("feasible", "infeasible")

    def test_s2_haco(self):
        req = _make_request(_s2_orders(), vehicles=_s2_vehicles(), mode=AlgorithmMode.HACO,
                            haco_time_limit=3.0, overall_time_limit=4.0)
        r = _run_and_record(req, "S2-HACO")
        assert r["status"] in ("feasible", "infeasible")
        assert r["runtime_ms"] < 5000

    def test_s2_hybrid(self):
        req = _make_request(_s2_orders(), vehicles=_s2_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=3.0, overall_time_limit=4.0)
        r = _run_and_record(req, "S2-HYBRID")
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS3:
    def test_s3_baseline(self):
        req = _make_request(_s3_orders(), vehicles=_s3_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S3-BASELINE")
        assert r["status"] in ("feasible", "infeasible")

    def test_s3_haco(self):
        req = _make_request(_s3_orders(), vehicles=_s3_vehicles(), mode=AlgorithmMode.HACO,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S3-HACO")
        assert r["status"] in ("feasible", "infeasible")
        assert r["runtime_ms"] < 7000

    def test_s3_hybrid(self):
        req = _make_request(_s3_orders(), vehicles=_s3_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S3-HYBRID")
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS4:
    def test_s4_baseline(self):
        req = _make_request(_s4_orders(), vehicles=_s4_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S4-BASELINE")
        assert r["status"] in ("feasible", "infeasible")

    def test_s4_haco(self):
        req = _make_request(_s4_orders(), vehicles=_s4_vehicles(), mode=AlgorithmMode.HACO,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S4-HACO")
        assert r["status"] in ("feasible", "infeasible")
        assert r["runtime_ms"] < 7000

    def test_s4_hybrid(self):
        req = _make_request(_s4_orders(), vehicles=_s4_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S4-HYBRID")
        assert r["status"] in ("feasible", "infeasible")


@pytest.mark.slow
class TestBenchmarkS5:
    def test_s5_baseline(self):
        req = _make_request(_s5_orders(), vehicles=_s5_vehicles(), mode=AlgorithmMode.BASELINE)
        r = _run_and_record(req, "S5-BASELINE")
        assert r["status"] in ("feasible", "infeasible")

    def test_s5_haco(self):
        req = _make_request(_s5_orders(), vehicles=_s5_vehicles(), mode=AlgorithmMode.HACO,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S5-HACO")
        assert r["status"] in ("feasible", "infeasible")
        assert r["runtime_ms"] < 7000

    def test_s5_hybrid(self):
        req = _make_request(_s5_orders(), vehicles=_s5_vehicles(), mode=AlgorithmMode.HYBRID,
                            haco_time_limit=4.0, overall_time_limit=5.0)
        r = _run_and_record(req, "S5-HYBRID")
        assert r["status"] in ("feasible", "infeasible")
