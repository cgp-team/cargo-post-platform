"""V2-9：Benchmark 场景测试。

场景：
- PASSENGER_ONLY
- CARGO_ONLY
- MIXED
- HIGH_CARGO
- NEAR_ROUTE
- FAR_FROM_ROUTE
- TIME_TIGHT
- MULTI_VEHICLE

记录：
- vehicleCount
- totalDistance
- totalDuration
- solverTimeMs
"""

from __future__ import annotations

import time

import pytest

from app.models import (
    OrderType, PlanOrder, PlanRequest, PlanShipment,
    Station, Vehicle,
)
from app.solver import solve


def _stations(n: int = 10):
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(n)
    ]


def _benchmark(req: PlanRequest) -> dict:
    """运行求解并记录指标。"""
    start = time.monotonic()
    outcome = solve(req)
    elapsed_ms = (time.monotonic() - start) * 1000

    return {
        "status": outcome.status,
        "reason_code": outcome.reason_code,
        "vehicle_count": len(outcome.vehicle_plans),
        "total_distance": outcome.total_distance,
        "solver_time_ms": round(elapsed_ms, 1),
    }


# ── PASSENGER_ONLY ────────────────────────────────────────────

def test_benchmark_passenger_only():
    """纯乘客场景。"""
    stations = _stations()

    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{i % 9 + 1}", alightingStationId=f"S{(i + 3) % 9 + 1}")
        for i in range(10)
    ]

    req = PlanRequest(
        requestId="bench-passenger",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 4)],
        orders=orders,
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000  # 10 秒内


# ── CARGO_ONLY ────────────────────────────────────────────────

def test_benchmark_cargo_only():
    """纯货运场景。"""
    stations = _stations()

    orders = [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{i % 9 + 1}", itemCount=1)
        for i in range(8)
    ]

    req = PlanRequest(
        requestId="bench-cargo",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 4)],
        orders=orders,
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000


# ── MIXED ─────────────────────────────────────────────────────

def test_benchmark_mixed():
    """混合场景：乘客 + 货运。"""
    stations = _stations()

    orders = (
        [PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                   boardingStationId=f"S{i % 9 + 1}", alightingStationId=f"S{(i + 3) % 9 + 1}")
         for i in range(6)]
        + [PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                     stationId=f"S{i % 9 + 1}", itemCount=1)
           for i in range(4)]
    )

    req = PlanRequest(
        requestId="bench-mixed",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 4)],
        orders=orders,
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000


# ── HIGH_CARGO ────────────────────────────────────────────────

def test_benchmark_high_cargo():
    """高货运量场景。"""
    stations = _stations()

    orders = [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{i % 9 + 1}", itemCount=1)
        for i in range(10)
    ]

    req = PlanRequest(
        requestId="bench-high-cargo",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 4)],
        orders=orders,
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000


# ── NEAR_ROUTE ────────────────────────────────────────────────

def test_benchmark_near_route():
    """近路线场景：货运点靠近骨架。"""
    stations = _stations()

    req = PlanRequest(
        requestId="bench-near",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                          skeleton=["S1", "S2", "S3"])],
        orders=[],
        shipments=[
            PlanShipment(shipmentId=f"TP{i}", pickupStationId=f"S{i + 1}",
                         deliveryStationId=f"S{i + 2}", quantity=1)
            for i in range(3)
        ],
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000


# ── FAR_FROM_ROUTE ────────────────────────────────────────────

def test_benchmark_far_from_route():
    """远路线场景：货运点远离骨架。"""
    far_stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.1, latitude=30.0 + i * 0.1)
        for i in range(6)
    ]

    req = PlanRequest(
        requestId="bench-far",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=far_stations[0],
        stations=far_stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                          skeleton=["S1", "S2", "S3"])],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S4",
                         deliveryStationId="S5", quantity=3),
        ],
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000


# ── TIME_TIGHT ────────────────────────────────────────────────

def test_benchmark_time_tight():
    """时间紧张场景。"""
    stations = _stations()

    req = PlanRequest(
        requestId="bench-tight",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T10:00:00+08:00",  # 2 小时
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
        ],
    )

    result = _benchmark(req)
    assert result["status"] in ("feasible", "infeasible")
    assert result["solver_time_ms"] < 10000


# ── MULTI_VEHICLE ─────────────────────────────────────────────

def test_benchmark_multi_vehicle():
    """多车辆场景。"""
    stations = _stations()

    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{i % 9 + 1}", alightingStationId=f"S{(i + 3) % 9 + 1}")
        for i in range(12)
    ]

    req = PlanRequest(
        requestId="bench-multi",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4) for i in range(1, 4)],
        orders=orders,
    )

    result = _benchmark(req)
    assert result["status"] == "feasible"
    assert result["solver_time_ms"] < 10000
