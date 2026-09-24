# -*- coding: utf-8 -*-
"""A/B：同一请求，use_branch_ranker=off vs force，对比耗时/目标/用车/停靠。"""
from __future__ import annotations

import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "algorithm"))

from app.models import (
    AlgorithmConfig, AlgorithmMode, CargoSource, OrderType,
    PlanOrder, PlanRequest, PlanShipment, Station, Vehicle,
)
from app.solver import solve

TZ = timezone(timedelta(hours=8))
START = datetime(2026, 3, 18, 7, 0, tzinfo=TZ)
END = datetime(2026, 3, 18, 19, 0, tzinfo=TZ)

# 346 线真实站（子集 + 服务点）
ST = {
    "CYU": ("重庆邮电大学", 106.6083, 29.5333),
    "CWYK": ("崇文路口", 106.6058, 29.5312),
    "HJY": ("黄桷垭", 106.5985, 29.5228),
    "HJYZJ": ("黄桷垭正街", 106.5952, 29.5185),
    "LJD": ("老君洞", 106.5920, 29.5148),
    "SZ": ("四中", 106.5895, 29.5285),
    "SXS": ("上新街", 106.5855, 29.5522),
    "XHL": ("下浩", 106.5882, 29.5485),
    "LMH": ("龙门浩", 106.5900, 29.5555),
    "HTX": ("海棠溪", 106.5825, 29.5420),
    "NBL": ("南滨路", 106.5785, 29.5555),
    "ZSXD": ("字水霄灯", 106.5725, 29.5588),
    "SHSD": ("石黄隧道", 106.5685, 29.5565),
    "QXG": ("七星岗", 106.5650, 29.5550),
    "JCK": ("较场口", 106.5738, 29.5528),
    "F-KDG": ("崇文路快递驿站", 106.6060, 29.5348),
    "F-NCM": ("黄桷垭农贸市场", 106.5975, 29.5195),
    "F-XHL-YL": ("下浩养老服务中心", 106.5890, 29.5472),
}
SKELETON = ["CWYK", "HJY", "HJYZJ", "LJD", "SZ", "SXS", "XHL", "LMH", "HTX", "NBL", "ZSXD", "SHSD", "QXG", "JCK"]


def _s(sid: str) -> Station:
    return Station(stationId=sid, longitude=ST[sid][1], latitude=ST[sid][2])


def build_request(mode: str) -> PlanRequest:
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER, boardingStationId="CYU", alightingStationId="JCK", economicValue=12),
        PlanOrder(orderId="P2", orderType=OrderType.PASSENGER, boardingStationId="HJY", alightingStationId="SXS", economicValue=8),
        PlanOrder(orderId="P3", orderType=OrderType.PASSENGER, boardingStationId="LJD", alightingStationId="QXG", economicValue=9),
        PlanOrder(orderId="P4", orderType=OrderType.PASSENGER, boardingStationId="XHL", alightingStationId="JCK", economicValue=7),
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY, stationId="F-KDG", itemCount=3, weightKg=12, volumeM3=0.08,
                  cargoSource=CargoSource.PRELOADED, economicValue=18),
        PlanOrder(orderId="D2", orderType=OrderType.DELIVERY, stationId="F-XHL-YL", itemCount=2, weightKg=6, volumeM3=0.04,
                  cargoSource=CargoSource.PRELOADED, economicValue=14),
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId="F-NCM", itemCount=2, weightKg=18, volumeM3=0.1, economicValue=35),
    ]
    ships = [
        PlanShipment(shipmentId="T1", pickupStationId="F-NCM", deliveryStationId="HTX", quantity=2, weightKg=20, volumeM3=0.12, economicValue=40),
        PlanShipment(shipmentId="T2", pickupStationId="F-KDG", deliveryStationId="F-XHL-YL", quantity=2, weightKg=8, volumeM3=0.05, economicValue=25),
        PlanShipment(shipmentId="T3", pickupStationId="SZ", deliveryStationId="JCK", quantity=1, weightKg=5, volumeM3=0.03, economicValue=15),
    ]
    need = ["CYU", *SKELETON, "F-KDG", "F-NCM", "F-XHL-YL"]
    return PlanRequest(
        requestId=f"AB-{mode}",
        batchStart=START,
        batchEnd=END,
        depot=_s("CYU"),
        stations=[_s(s) for s in need if s != "CYU"],
        vehicles=[Vehicle(
            vehicleId=34601, passengerCapacity=16, cargoCapacity=36,
            initialPassengerLoad=0, initialCargoLoad=5,
            cargoWeightCapacityKg=400.0, cargoVolumeCapacityM3=3.0,
            skeleton=SKELETON,
        )],
        orders=orders,
        shipments=ships,
        algorithmConfig=AlgorithmConfig(
            algorithmMode=AlgorithmMode.HACO,
            randomSeed=20260318,
            max_iterations=8,
            ant_count=16,
            maxDetourDistanceKm=3.0,
            use_branch_ranker=mode,
        ),
    )


def summarize(out, label: str, elapsed: float) -> dict:
    stops = sum(len(p.stops) for p in out.vehicle_plans)
    return {
        "label": label,
        "status": out.status,
        "reason": out.reason_code,
        "elapsed_s": round(elapsed, 3),
        "vehicles": len(out.vehicle_plans),
        "totalDistance": round(out.total_distance, 3),
        "stops": stops,
        "unassigned": out.unassigned_order_ids,
        "warnings": [w for w in (out.warnings or []) if "MS=" in w or "ITERATION" in w or "ML" in w or "SEED" in w][:8],
    }


def main():
    print("=" * 64)
    print("Branch Ranker A/B · 同请求同种子 · off vs force（只重排不硬砍）")
    print("=" * 64)
    results = []
    for mode in ("off", "force"):
        req = build_request(mode)
        t0 = time.perf_counter()
        out = solve(req)
        elapsed = time.perf_counter() - t0
        s = summarize(out, mode, elapsed)
        results.append(s)
        print(f"\n【{mode}】 {s['status']}  耗时 {s['elapsed_s']}s")
        print(f"  用车 {s['vehicles']}  总里程 {s['totalDistance']}  停靠 {s['stops']}  未分配 {s['unassigned']}")
        print(f"  warn: {s['warnings']}")

    a, b = results[0], results[1]
    print("\n" + "=" * 64)
    print("对比（force 相对 off）")
    dt = b["elapsed_s"] - a["elapsed_s"]
    print(f"  耗时: {a['elapsed_s']}s → {b['elapsed_s']}s  ({dt:+.3f}s)")
    print(f"  里程: {a['totalDistance']} → {b['totalDistance']}")
    print(f"  停靠: {a['stops']} → {b['stops']}")
    print(f"  状态: {a['status']} → {b['status']}")
    print("\n说明：smoke-1k 安全门 feasible_recall=0.73 < 0.80，auto 模式会关闭排序；")
    print("      force 为实验通道（Quality Fuse 重排、不硬砍），用于观察搜索顺序差异。")

    # 确认 ranker 实际参与
    from app.haco.ml_ranker import get_ranker
    from app.haco import construction as C
    rk = get_ranker()
    print(f"\n模型: {rk.version}  enabled={rk.enabled}  can_rank={rk.can_rank}  rerank_calls={rk.rerank_calls}")
    print(f"last_stats: {getattr(C.generate_insertion_candidates, 'last_stats', {})}")


if __name__ == "__main__":
    main()
