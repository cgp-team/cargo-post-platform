"""P1-7：Cargo Shipment 配对设计测试（目标行为）。

这些测试定义了未来 shipment pair 模型的目标行为。
当前 API 不支持 shipmentId，这些测试记录设计意图。
"""

from __future__ import annotations

import pytest

from app.models import OrderType, PlanOrder, PlanRequest, Station, StopAction, Vehicle
from app.solver import solve


def test_single_direction_delivery_valid():
    """单向 DELIVERY（无 PICKUP 配对）是合法的出程派送。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
    ]

    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S1", itemCount=1),
    ]

    req = PlanRequest(
        requestId="single-delivery",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_single_direction_pickup_valid():
    """单向 PICKUP（无 DELIVERY 配对）是合法的返程揽收。"""
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
    ]

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S1", itemCount=1),
    ]

    req = PlanRequest(
        requestId="single-pickup",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"


def test_pickup_before_delivery_on_same_order_concept():
    """设计意图：如果未来有 shipment pair，同一 shipmentId 的
    PICKUP 必须在 DELIVERY 之前。

    当前 API 不支持 shipmentId，此测试记录设计意图。
    当前实现：两个独立的 order（K1=PICKUP, D1=DELIVERY）可以独立安排。
    """
    stations = [
        Station(stationId="S0", longitude=104.000, latitude=30.000),
        Station(stationId="S1", longitude=104.010, latitude=30.010),
        Station(stationId="S2", longitude=104.020, latitude=30.020),
    ]

    # 当前 API：两个独立 order
    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S1", itemCount=5),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY,
                  stationId="S2", itemCount=5),
    ]

    req = PlanRequest(
        requestId="pickup-delivery-pair",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations[1:],
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    # 验证两个 stop 都被服务
    plan = outcome.vehicle_plans[0]
    served = {s.orderId for s in plan.stops if s.orderId}
    assert "K1" in served
    assert "D1" in served
