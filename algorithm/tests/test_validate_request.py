"""validate_request 输入校验测试（shipments 站点引用 / 起终点 / 规模上限）。

覆盖 P0 修复：shipment 引用未知站点或揽收站==送达站时不得触发 solver KeyError 崩溃，
必须在 validate_request 阶段返回 400；shipments 数量计入 25 单规模上限返回 413。
"""

from __future__ import annotations

import uuid

from fastapi.testclient import TestClient

from app.main import app, jobs


def _unique_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:12]}"


def _base_payload(request_id: str) -> dict:
    return {
        "requestId": request_id,
        "batchStart": "2026-08-26T08:00:00+08:00",
        "batchEnd": "2026-08-26T23:00:00+08:00",
        "depot": {"stationId": "S0", "longitude": 104.0, "latitude": 30.0},
        "stations": [
            {"stationId": "S1", "longitude": 104.01, "latitude": 30.01},
            {"stationId": "S2", "longitude": 104.02, "latitude": 30.02},
        ],
        "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 10}],
        "orders": [],
        "shipments": [],
    }


def _post(payload: dict):
    jobs.clear()
    with TestClient(app) as client:
        return client.post("/api/v1/plan", json=payload)


def _shipment(**overrides) -> dict:
    shipment = {"shipmentId": "SHP1", "pickupStationId": "S1", "deliveryStationId": "S2", "quantity": 1}
    shipment.update(overrides)
    return shipment


def test_shipment_unknown_pickup_station_400():
    payload = _base_payload(_unique_id("shp-unknown-pickup"))
    payload["shipments"] = [_shipment(pickupStationId="S99")]
    assert _post(payload).status_code == 400


def test_shipment_unknown_delivery_station_400():
    payload = _base_payload(_unique_id("shp-unknown-delivery"))
    payload["shipments"] = [_shipment(deliveryStationId="S99")]
    assert _post(payload).status_code == 400


def test_shipment_same_pickup_delivery_400():
    payload = _base_payload(_unique_id("shp-same"))
    payload["shipments"] = [_shipment(deliveryStationId="S1")]
    assert _post(payload).status_code == 400


def test_shipment_counts_toward_order_limit_413():
    payload = _base_payload(_unique_id("shp-limit"))
    payload["shipments"] = [
        {"shipmentId": f"SHP{i}", "pickupStationId": "S1", "deliveryStationId": "S2", "quantity": 1}
        for i in range(26)
    ]
    assert _post(payload).status_code == 413


def test_shipment_valid_reference_not_rejected():
    payload = _base_payload(_unique_id("shp-ok"))
    payload["shipments"] = [_shipment()]
    resp = _post(payload)
    # 合法引用不应被校验拦截（可行或业务不可行都是 200，而非 400/413）
    assert resp.status_code == 200
    assert resp.json()["status"] == "feasible"


def test_plan_internal_error_returns_structured_500(monkeypatch):
    """求解器异常不得裸 500：应返回结构化 ErrorResponse（code=ALGORITHM_INTERNAL_ERROR）。"""
    import app.main as main_module

    def boom(_request):
        raise KeyError("simulated solver crash")

    monkeypatch.setattr(main_module, "build_result", boom)
    payload = _base_payload(_unique_id("internal-error"))
    payload["orders"] = [{"orderId": "O1", "orderType": "DELIVERY", "stationId": "S1", "itemCount": 1}]
    resp = _post(payload)
    assert resp.status_code == 500
    body = resp.json()
    assert body["code"] == "ALGORITHM_INTERNAL_ERROR"
