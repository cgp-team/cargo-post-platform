"""DISPATCH_CORE_V047 运行时接线 + failure-case 输出回归。"""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

from app.dispatch_opt.failure_cases import (
    DispatchFailureCase,
    DispatchFailureCategory,
    classify_reason,
    worst_cases,
    write_failure_cases,
)
from app.main import app


def _payload(**over):
    payload = {
        "order": {
            "orderId": "O-DISPATCH-1",
            "pickupServicePoint": "PU",
            "deliveryServicePoint": "DE",
            "economicValue": 40.0,
            "deliveryDeadline": 100000.0,
        },
        "stations": [
            {"stationId": "PU", "longitude": 106.50, "latitude": 29.50},
            {"stationId": "DE", "longitude": 106.56, "latitude": 29.52},
        ],
        "currentTrips": [
            {
                "routeId": "347",
                "shiftId": "07:30",
                "vehicleId": 1,
                "departureTime": 0.0,
                "executionState": "PLANNED",
                "currentLatitude": 29.50,
                "currentLongitude": 106.50,
                "remainingCargoCapacity": 3,
                "tripDetourRemainingM": 50000.0,
                "passengerImpactBudgetS": 100000.0,
            }
        ],
        "futureTrips": [
            {
                "routeId": "347",
                "shiftId": "08:00",
                "vehicleId": 2,
                "departureTime": 1800.0,
                "executionState": "PLANNED",
                "currentLatitude": 29.50,
                "currentLongitude": 106.50,
                "remainingCargoCapacity": 3,
                "tripDetourRemainingM": 50000.0,
                "passengerImpactBudgetS": 100000.0,
            }
        ],
        "now": 1000.0,
    }
    payload.update(over)
    return payload


def test_dispatch_endpoint_assigns_and_returns_trace():
    client = TestClient(app)
    resp = client.post("/api/v1/dispatch/allocate", json=_payload())
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ASSIGNED"
    assert body["chosen"]["type"] == "CURRENT_TRIP"
    assert body["trace"]["whySelected"]
    assert body["trace"]["candidates"]
    # 成本必须显式标注是否 formal：有正式路网时 True 且增量距离>0；
    # 无路网时 False（禁止把直线标成正式成本）。
    assert isinstance(body["chosen"]["costIsFormal"], bool)
    if body["chosen"]["costIsFormal"]:
        # formal 真实道路：允许 0 绕行（订单本就在路径上），但必须产出非零真实时长/成本
        assert body["chosen"]["incrementalDurationS"] > 0
        assert body["chosen"]["incrementalCost"] > 0


def test_dispatch_endpoint_unknown_state_holds_not_rejects():
    client = TestClient(app)
    payload = _payload()
    payload["currentTrips"][0]["networkKnown"] = False
    payload["futureTrips"] = []
    resp = client.post("/api/v1/dispatch/allocate", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "HOLD"
    reasons = {c["reasonCode"] for c in body["trace"]["candidates"]}
    assert "UNKNOWN_PENDING_CONFIRMATION" in reasons


def test_global_haco_only_triggered_when_allowed_and_justified():
    client = TestClient(app)
    # 无可行候选 + 高价值 + 允许全局
    payload = _payload(
        order={
            "orderId": "O-HACO",
            "pickupServicePoint": "PU",
            "deliveryServicePoint": "DE",
            "isHighValue": True,
            "economicValue": 900.0,
        },
        currentTrips=[
            {
                "routeId": "347",
                "shiftId": "07:30",
                "vehicleId": 1,
                "departureTime": 0.0,
                "executionState": "COMPLETED",
                "currentLatitude": 29.50,
                "currentLongitude": 106.50,
                "remainingCargoCapacity": 0,
                "tripDetourRemainingM": 0.0,
            }
        ],
        futureTrips=[],
        globalHacoAvailable=True,
    )
    body = client.post("/api/v1/dispatch/allocate", json=payload).json()
    assert body["level"] == "LEVEL_4_GLOBAL_HACO"
    assert body["globalHacoTriggerReason"] == "HIGH_VALUE_ORDER"
    assert body["globalHacoTriggerCount"] == 1


def test_global_haco_not_triggered_when_not_available():
    client = TestClient(app)
    payload = _payload(order={"orderId": "O-NOHACO", "pickupServicePoint": "PU", "deliveryServicePoint": "DE"})
    payload["currentTrips"][0]["executionState"] = "COMPLETED"
    payload["currentTrips"][0]["remainingCargoCapacity"] = 0
    payload["futureTrips"] = []
    body = client.post("/api/v1/dispatch/allocate", json=payload).json()
    assert body["level"] == "LEVEL_5_HOLD"
    assert body["globalHacoTriggerReason"] is None


# ═══════════ failure case 分类 + Worst-20 输出 ═══════════


def test_failure_case_reason_classification():
    assert classify_reason("ROUTE_UNKNOWN") is DispatchFailureCategory.ROUTING_FALLBACK
    assert classify_reason("LOCATION_STALE") is DispatchFailureCategory.REACHABILITY_ERROR
    assert classify_reason("ETA_MISSED") is DispatchFailureCategory.SLA_ERROR
    assert classify_reason("LOCKED_ACTIVE_TRIP") is DispatchFailureCategory.LOCK_ERROR
    assert classify_reason("HANDOVER_INFEASIBLE") is DispatchFailureCategory.HANDOVER_ERROR
    assert classify_reason("DETOUR_TOO_LARGE") is DispatchFailureCategory.DETOUR_ERROR
    assert classify_reason("PASSENGER_IMPACT_EXCEEDED") is DispatchFailureCategory.PASSENGER_ERROR
    assert classify_reason("SOMETHING_NEW") is DispatchFailureCategory.UNKNOWN


def test_worst_cases_returns_top20_by_severity():
    cases = [
        DispatchFailureCase(case_id=f"c{i}", category=DispatchFailureCategory.UNKNOWN, severity=float(i))
        for i in range(30)
    ]
    top = worst_cases(cases, 20)
    assert len(top) == 20
    assert top[0].case_id == "c29"


def test_failure_cases_json_written_and_parseable(tmp_path):
    cases = [
        DispatchFailureCase(
            case_id="ROUTE_UNKNOWN_case",
            category=DispatchFailureCategory.ROUTING_FALLBACK,
            order_id="O1",
            candidate_type="CURRENT_TRIP",
            reason_code="ROUTE_UNKNOWN",
            severity=3.0,
        ),
        DispatchFailureCase(
            case_id="ETA_case",
            category=DispatchFailureCategory.SLA_ERROR,
            order_id="O2",
            reason_code="ETA_MISSED",
            severity=5.0,
        ),
    ]
    path = write_failure_cases(cases, path=tmp_path / "dispatch_v047_failure_cases.json", round_label="test")
    data = json.loads(path.read_text(encoding="utf-8"))
    assert data["version"] == "DISPATCH_CORE_V047"
    assert data["total"] == 2
    assert data["worst20"][0]["reason_code"] == "ETA_MISSED"
    assert data["byCategory"]["SLA_ERROR"] == 1


# ═══════════ candidate pool 漏检诊断 ═══════════


def test_candidate_pool_diagnostics_no_miss_without_truncation():
    from app.dispatch_opt import (
        DispatchOrder,
        DispatchRequest,
        DynamicDispatchCoordinator,
        TripView,
        diagnose_candidate_pool,
    )

    coords = {"PU": (30.06, 104.0), "DE": (30.0, 104.06)}
    trip = TripView(
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        departure_time=0.0,
        current_location=(30.0, 104.0),
        remaining_cargo_capacity=3,
        trip_detour_remaining_m=50000.0,
        passenger_impact_budget_s=100000.0,
        station_coords=coords,
    )
    plan = DynamicDispatchCoordinator(now=0.0).plan(
        DispatchRequest(
            order=DispatchOrder(
                order_id="P",
                pickup_service_point="PU",
                delivery_service_point="DE",
                economic_value=10.0,
                delivery_deadline=100000.0,
            ),
            current_trips=[trip],
        )
    )
    diag = diagnose_candidate_pool(plan)
    assert diag.pool_miss is False
    assert diag.reason_code == "NO_POOL_MISS"
    assert diag.full_checked_count == diag.screened_count == diag.pool_size

    # 一旦引入截断（pool_size=0），真正可行候选落在 pool 外 → CANDIDATE_POOL_MISS
    truncated = diagnose_candidate_pool(plan, pool_size=0)
    assert truncated.pool_miss is True
    assert truncated.reason_code == "CANDIDATE_POOL_MISS"
