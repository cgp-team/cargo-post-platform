"""V2-7：Time Dimension 测试。

验证：
- travel duration
- service duration
- batchStart / batchEnd 约束
- 与 CargoLoad / Passenger 维度共存
"""

from __future__ import annotations

from app.models import (
    AlgorithmConfig, OrderType, PlanOrder, PlanRequest, PlanShipment,
    Station, StopAction, Vehicle,
)
from app.solver import solve


def _stations():
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── 基本时间窗口 ──────────────────────────────────────────────

def test_time_window_within_batch():
    """路线在 batchStart ~ batchEnd 内完成。"""
    stations = _stations()

    req = PlanRequest(
        requestId="time-1",
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


def test_time_window_exceeded():
    """路线超出 batchEnd → INFEASIBLE。"""
    stations = _stations()

    req = PlanRequest(
        requestId="time-2",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T08:00:01+08:00",  # 只有 1 秒
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S2"),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "infeasible"
    assert outcome.reason_code == "TIME_WINDOW_EXCEEDED"


# ── 服务时间 ──────────────────────────────────────────────────

def test_service_duration_included():
    """服务时间计入总时间。"""
    stations = _stations()

    req = PlanRequest(
        requestId="time-3",
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

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    # 验证 segmentDuration 不为 None
    for stop in plan.stops[1:]:
        assert stop.segmentDuration is not None
        assert stop.segmentDuration >= 0


# ── 与 CargoLoad 共存 ─────────────────────────────────────────

def test_time_with_cargo():
    """时间窗口 + 货运。"""
    stations = _stations()

    req = PlanRequest(
        requestId="time-4",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S1",
                         deliveryStationId="S3", quantity=5),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


# ── 与 Passenger 共存 ─────────────────────────────────────────

def test_time_with_passenger():
    """时间窗口 + 乘客。"""
    stations = _stations()

    req = PlanRequest(
        requestId="time-5",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S3"),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


# ── 混合场景 ──────────────────────────────────────────────────

def test_time_with_all_dimensions():
    """时间窗口 + 乘客 + 货运 + 骨架。"""
    stations = _stations()

    req = PlanRequest(
        requestId="time-6",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10,
                          skeleton=["S1", "S2", "S3"])],
        orders=[
            PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                      boardingStationId="S1", alightingStationId="S3"),
        ],
        shipments=[
            PlanShipment(shipmentId="TP001", pickupStationId="S2",
                         deliveryStationId="S3", quantity=3),
        ],
    )

    outcome = solve(req)
    assert outcome.status == "feasible"
