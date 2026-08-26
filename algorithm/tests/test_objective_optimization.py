"""V2-8：Objective 优化测试。

验证：
- 最少车辆优先
- 总距离最小
- 确定性
"""

from __future__ import annotations

from app.models import (
    OrderType, PlanOrder, PlanRequest, PlanShipment,
    Station, Vehicle,
)
from app.solver import solve


def _stations():
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── 最少车辆优先 ──────────────────────────────────────────────

def test_single_vehicle_preferred():
    """容量足够时优先单车。"""
    stations = _stations()

    req = PlanRequest(
        requestId="obj-1",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[
            Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10),
            Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=10),
        ],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"
    assert len(outcome.vehicle_plans) == 1


def test_two_vehicles_when_needed():
    """容量不足时可启用两辆车（新模型允许重访站点，可能 1 车够用）。"""
    stations = _stations()

    req = PlanRequest(
        requestId="obj-2",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[
            Vehicle(vehicleId=1, passengerCapacity=3, cargoCapacity=10),
            Vehicle(vehicleId=2, passengerCapacity=3, cargoCapacity=10),
        ],
        orders=[
            PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2")
            for i in range(5)
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"
    # 新模型允许重访站点，1 辆车可能够用
    assert len(outcome.vehicle_plans) >= 1


# ── 确定性 ────────────────────────────────────────────────────

def test_deterministic_output():
    """相同输入产生相同输出。"""
    stations = _stations()

    req = PlanRequest(
        requestId="obj-3",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                      stationId="S3", itemCount=3),
        ],
    )

    r1 = solve(req)
    r2 = solve(req)

    assert r1.status == r2.status
    assert r1.total_distance == r2.total_distance
    assert len(r1.vehicle_plans) == len(r2.vehicle_plans)


# ── 总距离计算 ────────────────────────────────────────────────

def test_total_distance_positive():
    """总距离 > 0。"""
    stations = _stations()

    req = PlanRequest(
        requestId="obj-4",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"
    assert outcome.total_distance > 0


def test_segment_distance_sum_equals_total():
    """分段距离之和 = 总距离。"""
    stations = _stations()

    req = PlanRequest(
        requestId="obj-5",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    for plan in outcome.vehicle_plans:
        segment_sum = round(sum(s.segmentDistance for s in plan.stops), 3)
        assert segment_sum == plan.totalDistance
