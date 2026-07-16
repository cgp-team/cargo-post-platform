from fastapi.testclient import TestClient

from app.main import ALGORITHM_VERSION, app, jobs

client = TestClient(app)


def request_payload(scenario: str = "SUCCESS") -> dict:
    return {
        "requestId": f"req-{scenario}",
        "snapshotId": "snapshot-001",
        "planningTime": "2026-07-16T19:30:00+08:00",
        "rollingWindowMinutes": 30,
        "scenario": scenario,
        "algorithmConfig": {"algorithmVersion": ALGORITHM_VERSION, "parameterVersion": "test-v1"},
        "vehicles": [
            {
                "vehicleId": 1001,
                "shiftId": 2001,
                "longitude": 104.0668,
                "latitude": 30.5728,
                "passengerLoad": 0,
                "remainingCargoCapacityKg": 500,
                "status": "AVAILABLE",
            }
        ],
        "orders": [
            {
                "orderId": f"O-{index}",
                "orderType": "CARGO",
                "pickupNodeId": f"P-{index}",
                "deliveryNodeId": f"D-{index}",
                "itemCount": 1,
                "weightKg": 10,
                "volumeM3": 0.1,
                "earliestPickupTime": "2026-07-16T19:30:00+08:00",
                "latestDeliveryTime": "2026-07-16T21:30:00+08:00",
                "priority": 5,
            }
            for index in range(2)
        ],
    }


def create_and_finish(scenario: str) -> tuple[str, dict]:
    created = client.post("/api/algorithm/v1/planning-jobs", json=request_payload(scenario))
    assert created.status_code == 202
    job_id = created.json()["jobId"]
    assert client.get(f"/api/algorithm/v1/planning-jobs/{job_id}").json()["status"] == "RUNNING"
    status = client.get(f"/api/algorithm/v1/planning-jobs/{job_id}").json()
    return job_id, status


def setup_function() -> None:
    jobs.clear()


def test_health() -> None:
    assert client.get("/health").json() == {"status": "UP", "algorithmVersion": ALGORITHM_VERSION}


def test_success_round_robin_result() -> None:
    job_id, status = create_and_finish("SUCCESS")
    assert status["status"] == "SUCCESS"
    result = client.get(f"/api/algorithm/v1/planning-jobs/{job_id}/result")
    assert result.status_code == 200
    assert result.json()["vehiclePlans"][0]["assignedOrderIds"] == ["O-0", "O-1"]


def test_partial_rejection_has_assignment_and_rejection() -> None:
    job_id, status = create_and_finish("PARTIAL_REJECTION")
    assert status["status"] == "PARTIAL_SUCCESS"
    result = client.get(f"/api/algorithm/v1/planning-jobs/{job_id}/result").json()
    assert result["vehiclePlans"][0]["assignedOrderIds"] == ["O-0"]
    assert result["rejectedOrders"][0]["orderId"] == "O-1"


def test_no_feasible_solution() -> None:
    job_id, status = create_and_finish("NO_FEASIBLE_SOLUTION")
    assert status["status"] == "NO_FEASIBLE_SOLUTION"
    result = client.get(f"/api/algorithm/v1/planning-jobs/{job_id}/result").json()
    assert result["status"] == "NO_FEASIBLE_SOLUTION"
    assert len(result["rejectedOrders"]) == 2


def test_timeout() -> None:
    job_id, status = create_and_finish("TIMEOUT")
    assert status["status"] == "TIMEOUT"
    result = client.get(f"/api/algorithm/v1/planning-jobs/{job_id}/result")
    assert result.status_code == 409
    assert result.json()["code"] == "TIMEOUT"


def test_internal_error() -> None:
    response = client.post("/api/algorithm/v1/planning-jobs", json=request_payload("INTERNAL_ERROR"))
    assert response.status_code == 500
    assert response.json()["code"] == "ALGORITHM_INTERNAL_ERROR"


def test_timezone_is_required() -> None:
    payload = request_payload()
    payload["planningTime"] = "2026-07-16T19:30:00"
    assert client.post("/api/algorithm/v1/planning-jobs", json=payload).status_code == 422
