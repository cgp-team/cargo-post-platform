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


def test_time_dimension_avoids_short_but_overtime_path():
    """时间窗约束下，solver 应避开「距离短但超时」的路径，改选「稍远但满足时间窗」的路径。

    构造：D→S1→S2→D 距离短(3km) 但 S1→S2 段堵车(100s)，总时间 230s；
          D→S2→S1→D 距离长(15km) 但全程快(15s)，总时间 135s。
    窗口 140s：只有后者满足。旧 post-solve 会返回「距离最短」的前者再被拒绝；
    加 Time Dimension 后 solver 应直接返回后者（feasible）。
    """
    depot = Station(stationId="D", longitude=104.0, latitude=30.0)
    s1 = Station(stationId="S1", longitude=104.01, latitude=30.01)
    s2 = Station(stationId="S2", longitude=104.02, latitude=30.02)

    matrix = {
        ("D", "D"): (0.0, 0.0), ("D", "S1"): (1.0, 5.0), ("D", "S2"): (5.0, 5.0),
        ("S1", "D"): (5.0, 5.0), ("S1", "S1"): (0.0, 0.0), ("S1", "S2"): (1.0, 100.0),
        ("S2", "D"): (1.0, 5.0), ("S2", "S1"): (5.0, 5.0), ("S2", "S2"): (0.0, 0.0),
    }

    req = PlanRequest(
        requestId="time-guide",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T08:02:20+08:00",  # 140 秒窗口
        depot=depot,
        stations=[s1, s2],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=[
            PlanOrder(orderId="D1", orderType=OrderType.DELIVERY, stationId="S1", itemCount=1),
            PlanOrder(orderId="D2", orderType=OrderType.DELIVERY, stationId="S2", itemCount=1),
        ],
    )

    outcome = solve(req, matrix)
    assert outcome.status == "feasible"
    plan = outcome.vehicle_plans[0]
    deliver_order = [s.stationId for s in plan.stops if s.action == StopAction.DELIVER]
    # 必须避开 S1→S2 堵车段（否则 230s 超时），故 S2 应在 S1 之前
    assert deliver_order == ["S2", "S1"], f"应避开 S1→S2 堵车段，实际 {deliver_order}"


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
