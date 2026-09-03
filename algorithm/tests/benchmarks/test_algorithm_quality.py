"""算法质量 Benchmark 测试。

记录完整质量指标（不仅仅是 status == feasible）：
  - vehicle_count
  - total_distance
  - solver_time_ms
  - passenger_capacity_utilization
  - cargo_capacity_utilization
  - segment_distance_sum（验证一致性）
"""

from __future__ import annotations

import time

from app.models import (
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    StopAction,
    Vehicle,
)
from app.solver import solve


def _stations(n: int = 10) -> list[Station]:
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(n)
    ]


def _benchmark(req: PlanRequest) -> dict:
    """运行求解并记录完整质量指标。"""
    start = time.monotonic()
    outcome = solve(req)
    elapsed_ms = (time.monotonic() - start) * 1000

    total_passenger_capacity = sum(
        v.passengerCapacity for v in req.vehicles
    )
    total_cargo_capacity = sum(
        v.cargoCapacity for v in req.vehicles
    )

    # 统计实际峰值载荷
    peak_passenger = 0
    peak_cargo = 0
    for plan in outcome.vehicle_plans:
        load = 0
        for s in plan.stops:
            if s.action == StopAction.BOARD:
                load += 1
            elif s.action == StopAction.ALIGHT:
                load -= 1
        peak_passenger = max(peak_passenger, load)

        cargo = 0
        for s in plan.stops:
            if s.action == StopAction.DELIVER:
                cargo -= 1
            elif s.action == StopAction.PICKUP:
                cargo += 1
        peak_cargo = max(peak_cargo, cargo)

    seg_sum = round(
        sum(s.segmentDistance for p in outcome.vehicle_plans for s in p.stops), 3
    )

    return {
        "status": outcome.status,
        "reason_code": outcome.reason_code,
        "vehicle_count": len(outcome.vehicle_plans),
        "total_distance": outcome.total_distance,
        "segment_distance_sum": seg_sum,
        "solver_time_ms": round(elapsed_ms, 1),
        "passenger_capacity_utilization": (
            round(peak_passenger / total_passenger_capacity, 3)
            if total_passenger_capacity > 0 else 0
        ),
        "cargo_capacity_utilization": (
            round(peak_cargo / total_cargo_capacity, 3)
            if total_cargo_capacity > 0 else 0
        ),
    }


# ── PASSENGER_ONLY ────────────────────────────────────────────

def test_quality_passenger_only():
    """纯乘客场景。"""
    stations = _stations()
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{i % 9 + 1}", alightingStationId=f"S{(i + 3) % 9 + 1}")
        for i in range(10)
    ]
    req = PlanRequest(
        requestId="quality-passenger",
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
    assert result["vehicle_count"] >= 1
    assert result["total_distance"] > 0
    assert result["segment_distance_sum"] == result["total_distance"]


# ── CARGO_ONLY ────────────────────────────────────────────────

def test_quality_cargo_only():
    """纯货运场景。"""
    stations = _stations()
    orders = [
        PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                  stationId=f"S{i % 9 + 1}", itemCount=1)
        for i in range(8)
    ]
    req = PlanRequest(
        requestId="quality-cargo",
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
    assert result["total_distance"] > 0


# ── MIXED ─────────────────────────────────────────────────────

def test_quality_mixed():
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
        requestId="quality-mixed",
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


# ── SHIPMENT ──────────────────────────────────────────────────

def test_quality_shipment():
    """配对货运场景。"""
    stations = _stations(6)
    req = PlanRequest(
        requestId="quality-shipment",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
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


# ── SKELETON + CARGO ──────────────────────────────────────────

def test_quality_skeleton_cargo():
    """骨架 + 货运场景。"""
    stations = _stations(6)
    req = PlanRequest(
        requestId="quality-skeleton",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
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


# ── MULTI_VEHICLE ─────────────────────────────────────────────

def test_quality_multi_vehicle():
    """多车辆场景。"""
    stations = _stations()
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{i % 9 + 1}", alightingStationId=f"S{(i + 3) % 9 + 1}")
        for i in range(12)
    ]
    req = PlanRequest(
        requestId="quality-multi",
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
    assert result["vehicle_count"] >= 1
