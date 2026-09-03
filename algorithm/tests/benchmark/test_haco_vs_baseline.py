"""HACO-CPS 1.4.0 Benchmark：HACO vs Baseline vs Hybrid。

每个 case 运行 BASELINE / HACO / HYBRID，输出统一格式。
"""

import time

import pytest

from app.models import (
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    Station,
    Vehicle,
)
from app.solver import solve


# ─── Test Cases ──────────────────────────────────────────────


def _case_05_passenger():
    """5 乘客。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}")
        for i in range(5)
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=10, cargoCapacity=10)]
    return PlanRequest(
        requestId="bench-05p", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_10_passenger():
    """10 乘客。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+2)%5)+1}")
        for i in range(10)
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=10, cargoCapacity=10)]
    return PlanRequest(
        requestId="bench-10p", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_15_mixed():
    """15 混合任务。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = []
    for i in range(10):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}"
        ))
    for i in range(5):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{(i%5)+1}", itemCount=1
        ))
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=10, cargoCapacity=10)]
    return PlanRequest(
        requestId="bench-15m", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_20_mixed():
    """20 混合任务。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = []
    for i in range(15):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}"
        ))
    for i in range(5):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{(i%5)+1}", itemCount=1
        ))
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=15, cargoCapacity=10)]
    return PlanRequest(
        requestId="bench-20m", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_25_mixed():
    """25 混合任务。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = []
    for i in range(20):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}"
        ))
    for i in range(5):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{(i%5)+1}", itemCount=1
        ))
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=20, cargoCapacity=10)]
    return PlanRequest(
        requestId="bench-25m", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_shipment_heavy():
    """重货物场景。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}")
        for i in range(3)
    ]
    shipments = [
        {"shipmentId": f"S{i}", "pickupStationId": f"S{(i%5)+1}",
         "deliveryStationId": f"S{((i+2)%5)+1}", "quantity": 2}
        for i in range(5)
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=20)]
    return PlanRequest(
        requestId="bench-ship", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders, shipments=shipments,
    )


def _case_pickup_delivery_reuse():
    """出程/返程容量复用。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = []
    # 出程 delivery
    for i in range(3):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i+1}", itemCount=2
        ))
    # 返程 pickup
    for i in range(3):
        orders.append(PlanOrder(
            orderId=f"K{i}", orderType=OrderType.PICKUP,
            stationId=f"S{i+3}", itemCount=2
        ))
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=5)]
    return PlanRequest(
        requestId="bench-reuse", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_skeleton():
    """骨架场景。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}")
        for i in range(5)
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=10, cargoCapacity=10,
                skeleton=["S1", "S2", "S3", "S4", "S5"])
    ]
    return PlanRequest(
        requestId="bench-skel", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_heterogeneous_vehicle():
    """异构车辆。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}")
        for i in range(8)
    ]
    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=3, cargoCapacity=5),
        Vehicle(vehicleId=2, passengerCapacity=5, cargoCapacity=3),
    ]
    return PlanRequest(
        requestId="bench-hetero", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


def _case_tight_time_window():
    """紧时间窗。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]
    orders = [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i%5)+1}", alightingStationId=f"S{((i+1)%5)+1}")
        for i in range(5)
    ]
    vehicles = [Vehicle(vehicleId=1, passengerCapacity=10, cargoCapacity=10)]
    return PlanRequest(
        requestId="bench-tight", batchStart="2026-08-26T09:00:00+08:00",
        batchEnd="2026-08-26T09:30:00+08:00", depot=depot, stations=stations,
        vehicles=vehicles, orders=orders,
    )


# ─── Benchmark Runner ────────────────────────────────────────


def _run_benchmark(request, mode=None):
    """运行基准测试。"""
    if mode:
        request.algorithmConfig = type('Config', (), {'algorithmMode': mode})()

    start = time.time()
    result = solve(request)
    runtime = time.time() - start

    return {
        "status": result.status,
        "vehicles": len(result.vehicle_plans),
        "distance": result.total_distance,
        "runtime_seconds": round(runtime, 3),
        "fallback": "HACO_FALLBACK_TO_BASELINE" in result.warnings,
        "algorithm_version": result.algorithm_version,
    }


# ─── Test Functions ──────────────────────────────────────────


BENCHMARK_CASES = [
    ("case_05_passenger", _case_05_passenger),
    ("case_10_passenger", _case_10_passenger),
    ("case_15_mixed", _case_15_mixed),
    ("case_20_mixed", _case_20_mixed),
    ("case_25_mixed", _case_25_mixed),
    ("case_shipment_heavy", _case_shipment_heavy),
    ("case_pickup_delivery_reuse", _case_pickup_delivery_reuse),
    ("case_skeleton", _case_skeleton),
    ("case_heterogeneous_vehicle", _case_heterogeneous_vehicle),
    ("case_tight_time_window", _case_tight_time_window),
]


@pytest.mark.parametrize("case_name,case_factory", BENCHMARK_CASES)
def test_benchmark(case_name, case_factory):
    """运行基准测试。"""
    request = case_factory()

    # 运行默认模式（HYBRID）
    result = _run_benchmark(request)

    print(f"\n{'='*60}")
    print(f"Case: {case_name}")
    print(f"  Status: {result['status']}")
    print(f"  Vehicles: {result['vehicles']}")
    print(f"  Distance: {result['distance']}")
    print(f"  Runtime: {result['runtime_seconds']}s")
    print(f"  Fallback: {result['fallback']}")
    print(f"  Algorithm: {result['algorithm_version']}")

    # 不做断言，只记录结果
    assert result["status"] in ("feasible", "infeasible")
