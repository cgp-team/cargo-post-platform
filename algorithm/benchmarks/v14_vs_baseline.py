"""Step8 基准：HACO-CPS 1.4.0（v14_solver 主链） vs OR-Tools Baseline。

跑一组同请求、不同规模的场景，对比 BASELINE / HACO / HYBRID 三个 mode：
  status / vehicle 数 / total_distance / 求解耗时 / algorithm_version。

运行：  cd algorithm && python benchmarks/v14_vs_baseline.py
输出：  控制台 Markdown 表 + JSON 摘要（可留档）。
"""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone

from app.models import (
    AlgorithmConfig,
    CargoSource,
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    Vehicle,
)
from app.solver import solve


def _st(sid: str, lon: float, lat: float) -> Station:
    return Station(stationId=sid, longitude=lon, latitude=lat)


def _scenario(size: str):
    """构造不同规模场景。station 沿一条线摆放，车辆走公交骨架。"""
    if size == "S":
        n_stations, n_vehicles = 5, 1
        p, d, k, sh = 3, 1, 1, 1
    elif size == "M":
        n_stations, n_vehicles = 10, 2
        p, d, k, sh = 6, 3, 3, 2
    else:  # L
        n_stations, n_vehicles = 10, 3
        p, d, k, sh = 13, 6, 6, 0

    depot = _st("D", 0.0, 0.0)
    stations = [
        depot,
        *(
            _st(f"S{i}", 0.005 * i, 0.002 * (i % 3))
            for i in range(1, n_stations + 1)
        ),
    ]
    vehicles = [
        Vehicle(
            vehicleId=1000 + i,
            passengerCapacity=10,
            cargoCapacity=8,
            initialCargoLoad=0,
            skeleton=[f"S{j}" for j in range(1, n_stations + 1)],
        )
        for i in range(n_vehicles)
    ]

    orders = []
    for i in range(1, p + 1):
        orders.append(PlanOrder(
            orderId=f"P{i}", orderType=OrderType.PASSENGER,
            boardingStationId=f"S{i % n_stations + 1}",
            alightingStationId=f"S{(i + 3) % n_stations + 1}",
        ))
    for i in range(1, d + 1):
        orders.append(PlanOrder(
            orderId=f"D{i}", orderType=OrderType.DELIVERY,
            stationId=f"S{i % n_stations + 1}", itemCount=1,
            cargoSource=CargoSource.PRELOADED,
        ))
    for i in range(1, k + 1):
        orders.append(PlanOrder(
            orderId=f"K{i}", orderType=OrderType.PICKUP,
            stationId=f"S{(i + 4) % n_stations + 1}", itemCount=1,
        ))
    # PRELOADED 派送由 initialCargoLoad 支撑
    total_pre = sum(o.itemCount for o in orders
                    if o.orderType == OrderType.DELIVERY
                    and o.cargoSource == CargoSource.PRELOADED)
    if total_pre > 0:
        per = -(-total_pre // n_vehicles)
        for v in vehicles:
            v.initialCargoLoad = per

    shipments = [
        PlanShipment(
            shipmentId=f"T{i}",
            pickupStationId=f"S{i % n_stations + 1}",
            deliveryStationId=f"S{(i + 2) % n_stations + 1}",
            quantity=2,
        )
        for i in range(1, sh + 1)
    ]
    return orders, shipments, stations, vehicles


def main() -> None:
    start = datetime(2026, 9, 1, 8, 0, tzinfo=timezone.utc)
    end = datetime(2026, 9, 1, 18, 0, tzinfo=timezone.utc)
    summary = []
    print("| size | mode | status | veh | distance | time(s) | version |")
    print("|---|---|---|---|---|---|---|")

    for size in ("S", "M", "L"):
        orders, shipments, stations, vehicles = _scenario(size)
        for mode in ("BASELINE", "HACO", "HYBRID"):
            cfg = AlgorithmConfig(
                ant_count=8, max_iterations=15,
                randomSeed=20260903, algorithmMode=mode,
            )
            req = PlanRequest(
                requestId=f"bench-{size}-{mode}",
                batchStart=start, batchEnd=end,
                depot=stations[0], stations=stations,
                vehicles=vehicles, orders=orders, shipments=shipments,
                algorithmConfig=cfg,
            )
            t0 = time.monotonic()
            out = solve(req)
            dt = time.monotonic() - t0
            row = {
                "size": size, "mode": mode,
                "status": out.status, "veh": len(out.vehicle_plans),
                "distance": round(out.total_distance, 3),
                "time_s": round(dt, 2),
                "version": out.algorithm_version,
            }
            summary.append(row)
            print(
                f"| {size} | {mode:8s} | {row['status']:9s} | {row['veh']} | "
                f"{row['distance']} | {row['time_s']} | {row['version']} |"
            )

    with open("benchmarks/v14_vs_baseline_summary.json", "w",
              encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)
    print("\nsummary -> benchmarks/v14_vs_baseline_summary.json")


if __name__ == "__main__":
    main()
