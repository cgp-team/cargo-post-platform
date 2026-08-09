from fastapi.testclient import TestClient

from app.main import ALGORITHM_VERSION, app, jobs

client = TestClient(app)

STATIONS = [
    {"stationId": "S1", "longitude": 1.0, "latitude": 2.0},
    {"stationId": "S2", "longitude": 1.0, "latitude": 5.0},
    {"stationId": "S3", "longitude": 2.0, "latitude": 8.0},
    {"stationId": "S4", "longitude": 3.0, "latitude": 3.0},
    {"stationId": "S5", "longitude": 4.0, "latitude": 7.0},
]


def passenger_order(index: int) -> dict:
    return {
        "orderId": f"O-P{index}",
        "orderType": "PASSENGER",
        "boardingStationId": "S1",
        "alightingStationId": "S2",
    }


def cargo_order(index: int, order_type: str = "DELIVERY") -> dict:
    return {
        "orderId": f"O-{order_type[0]}{index}",
        "orderType": order_type,
        "stationId": "S3",
        "itemCount": 1,
        "weightKg": 10,
        "volumeM3": 0.1,
    }


def request_payload(
    scenario: str = "SUCCESS",
    request_id: str = "req-001",
    vehicle_count: int = 1,
    orders: list | None = None,
) -> dict:
    if orders is None:
        orders = [passenger_order(1), cargo_order(1), cargo_order(2, "PICKUP")]
    return {
        "requestId": request_id,
        "batchStart": "2026-08-09T08:00:00+08:00",
        "batchEnd": "2026-08-09T08:30:00+08:00",
        "depot": {"stationId": "S0", "longitude": 0.0, "latitude": 0.0},
        "stations": STATIONS,
        "vehicles": [{"vehicleId": 1000 + index} for index in range(1, vehicle_count + 1)],
        "orders": orders,
        "scenario": scenario,
    }


def setup_function() -> None:
    jobs.clear()


def test_health() -> None:
    assert client.get("/health").json() == {"status": "UP", "algorithmVersion": ALGORITHM_VERSION}


def test_ready() -> None:
    assert client.get("/ready").status_code == 200


def test_success_single_vehicle() -> None:
    response = client.post("/api/v1/plan", json=request_payload())
    assert response.status_code == 200
    result = response.json()
    assert result["status"] == "feasible"
    assert result["cached"] is False
    assert len(result["vehiclePlans"]) == 1
    stops = result["vehiclePlans"][0]["stops"]
    assert stops[0]["action"] == "DEPART"
    assert stops[-1]["action"] == "RETURN"
    assert result["totalDistance"] > 0


def test_passenger_board_before_alight() -> None:
    result = client.post("/api/v1/plan", json=request_payload()).json()
    actions = [stop["action"] for stop in result["vehiclePlans"][0]["stops"]]
    assert actions.index("BOARD") < actions.index("ALIGHT")


def test_auto_second_vehicle_on_overload() -> None:
    orders = [passenger_order(index) for index in range(6)]
    payload = request_payload(vehicle_count=2, orders=orders)
    result = client.post("/api/v1/plan", json=payload).json()
    assert result["status"] == "feasible"
    assert len(result["vehiclePlans"]) == 2


def test_single_vehicle_preferred_when_capacity_enough() -> None:
    payload = request_payload(vehicle_count=3)
    result = client.post("/api/v1/plan", json=payload).json()
    assert result["status"] == "feasible"
    assert len(result["vehiclePlans"]) == 1


def test_over_capacity_infeasible() -> None:
    orders = [passenger_order(index) for index in range(6)]
    payload = request_payload(vehicle_count=1, orders=orders)
    result = client.post("/api/v1/plan", json=payload).json()
    assert result["status"] == "infeasible"
    assert result["reasonCode"] == "OVER_CAPACITY"


def test_idempotent_replay_returns_cached() -> None:
    payload = request_payload()
    first = client.post("/api/v1/plan", json=payload).json()
    second = client.post("/api/v1/plan", json=payload).json()
    assert second["cached"] is True
    assert second["totalDistance"] == first["totalDistance"]
    assert second["vehiclePlans"] == first["vehiclePlans"]


def test_timeout_then_poll_result() -> None:
    payload = request_payload(scenario="TIMEOUT", request_id="req-timeout")
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 408
    assert response.json()["code"] == "TIMEOUT"

    computing = client.get("/api/v1/result/req-timeout")
    assert computing.status_code == 202

    done = client.get("/api/v1/result/req-timeout")
    assert done.status_code == 200
    assert done.json()["status"] == "feasible"


def test_pending_resubmit_returns_408() -> None:
    payload = request_payload(scenario="TIMEOUT", request_id="req-pending")
    assert client.post("/api/v1/plan", json=payload).status_code == 408
    assert client.post("/api/v1/plan", json=payload).status_code == 408


def test_no_feasible_solution_scenario() -> None:
    payload = request_payload(scenario="NO_FEASIBLE_SOLUTION", request_id="req-infeasible")
    result = client.post("/api/v1/plan", json=payload).json()
    assert result["status"] == "infeasible"
    assert result["reasonCode"] == "TIMING_CONFLICT"


def test_partial_rejection_scenario() -> None:
    payload = request_payload(scenario="PARTIAL_REJECTION", request_id="req-partial")
    result = client.post("/api/v1/plan", json=payload).json()
    assert result["status"] == "infeasible"
    assert result["reasonCode"] == "PARTIAL_ONLY"


def test_internal_error_scenario() -> None:
    payload = request_payload(scenario="INTERNAL_ERROR", request_id="req-error")
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 500
    assert response.json()["code"] == "ALGORITHM_INTERNAL_ERROR"


def test_over_limit_413() -> None:
    orders = [passenger_order(index) for index in range(26)]
    payload = request_payload(vehicle_count=3, orders=orders)
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 413
    assert response.json()["code"] == "OVER_LIMIT"


def test_result_not_found() -> None:
    response = client.get("/api/v1/result/req-unknown")
    assert response.status_code == 404
    assert response.json()["code"] == "REQUEST_NOT_FOUND"


def test_unknown_station_400() -> None:
    payload = request_payload()
    payload["orders"][0]["boardingStationId"] = "S99"
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_INPUT"


def test_passenger_missing_stations_400() -> None:
    payload = request_payload()
    payload["orders"][0] = {"orderId": "O-BAD", "orderType": "PASSENGER", "boardingStationId": "S1"}
    response = client.post("/api/v1/plan", json=payload)
    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_INPUT"


def test_config_out_of_range_warns_but_accepted() -> None:
    payload = request_payload()
    payload["algorithmConfig"] = {"ant_count": 500}
    result = client.post("/api/v1/plan", json=payload).json()
    assert result["status"] == "feasible"
    assert any("ant_count" in warning for warning in result["warnings"])


def test_timezone_is_required() -> None:
    payload = request_payload()
    payload["batchStart"] = "2026-08-09T08:00:00"
    assert client.post("/api/v1/plan", json=payload).status_code == 422
