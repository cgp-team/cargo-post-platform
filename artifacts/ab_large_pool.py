# -*- coding: utf-8 -*-
"""大候选池 A/B：真实公交线骨架 + 真实坐标 20–25 单，off vs force。

对比 search_reduction（候选截断/耗时）与目标质量（里程/停靠/未分配）。
"""
from __future__ import annotations

import json
import random
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "algorithm"))

from app.haco import construction as C
from app.models import (
    AlgorithmConfig, AlgorithmMode, CargoSource, OrderType,
    PlanOrder, PlanRequest, PlanShipment, Station, Vehicle,
)
from app.solver import solve

TZ = timezone(timedelta(hours=8))
START = datetime(2026, 3, 18, 7, 0, tzinfo=TZ)
END = datetime(2026, 3, 18, 19, 0, tzinfo=TZ)
SNAP = ROOT / "algorithm" / "data" / "transit" / "snapshots" / "osm_routes_full.json"


def hav(a, b):
    lat1, lon1 = a
    lat2, lon2 = b
    import math
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(h))


def load_real_line(min_stops=10, seed=7):
    """挑一条真实 route=bus 长线 + 邻域真实站点坐标池。"""
    data = json.loads(SNAP.read_text(encoding="utf-8"))
    st = {s["station_id"]: s for s in data["stations"]}
    routes = [r for r in data["routes"] if len(r.get("stop_ids") or []) >= min_stops and r.get("name")]
    rng = random.Random(seed)
    rt = rng.choice(routes)
    stops = [sid for sid in rt["stop_ids"] if sid in st][:16]
    core = [st[s] for s in stops]
    lat = sum(s["latitude"] for s in core) / len(core)
    lon = sum(s["longitude"] for s in core) / len(core)
    near = []
    for s in st.values():
        if hav((lat, lon), (s["latitude"], s["longitude"])) <= 10.0:
            near.append(s)
    near.sort(key=lambda s: hav((lat, lon), (s["latitude"], s["longitude"])))
    pool, seen = [], set()
    for s in core + near:
        if s["station_id"] not in seen:
            seen.add(s["station_id"])
            pool.append(s)
        if len(pool) >= 40:
            break
    return rt, stops, pool, st


def _sid(i: int) -> str:
    return f"S{i:02d}"


def build_request(mode: str, n_orders: int = 22, candidate_size: int = 64, seed: int = 20260318):
    rt, stop_ids, pool, st = load_real_line(seed=seed)
    # 真实坐标映射到短名
    id2s = {}
    coords = {}
    for i, s in enumerate(pool):
        id2s[s["station_id"]] = _sid(i)
        coords[_sid(i)] = (s["longitude"], s["latitude"])
    skel = [id2s[s] for s in stop_ids if s in id2s]
    assert len(skel) >= 8, f"skeleton too short: {len(skel)}"

    rng = random.Random(seed)
    kinds = [OrderType.PASSENGER] * 5 + [OrderType.DELIVERY] * 3 + [OrderType.PICKUP] * 2
    orders: list[PlanOrder] = []
    ships: list[PlanShipment] = []
    sid_pool = list(coords.keys())

    # 客运：骨架站上下车
    for i in range(8):
        o, d = rng.sample(skel, 2)
        if skel.index(o) > skel.index(d):
            o, d = d, o
        orders.append(PlanOrder(
            orderId=f"P{i+1}", orderType=OrderType.PASSENGER,
            boardingStationId=o, alightingStationId=d,
            economicValue=float(rng.randint(6, 25)),
        ))
    # 快递进村 / 返程揽收：真实坐标单点
    for i in range(7):
        st_id = rng.choice(sid_pool)
        ot = OrderType.DELIVERY if i % 3 != 2 else OrderType.PICKUP
        orders.append(PlanOrder(
            orderId=f"{'D' if ot==OrderType.DELIVERY else 'K'}{i+1}",
            orderType=ot, stationId=st_id,
            itemCount=rng.randint(1, 4),
            weightKg=float(rng.randint(2, 25)),
            volumeM3=round(rng.uniform(0.02, 0.15), 3),
            cargoSource=CargoSource.PRELOADED if ot == OrderType.DELIVERY else None,
            economicValue=float(rng.randint(10, 45)),
        ))
    # 联运/农产品出山：真实坐标 OD
    for i in range(n_orders - 15):
        o, d = rng.sample(sid_pool, 2)
        ships.append(PlanShipment(
            shipmentId=f"T{i+1}",
            pickupStationId=o, deliveryStationId=d,
            quantity=rng.randint(1, 3),
            weightKg=float(rng.randint(5, 35)),
            volumeM3=round(rng.uniform(0.03, 0.2), 3),
            economicValue=float(rng.randint(15, 70)),
        ))

    need = set(skel) | set(coords)
    stations = [
        Station(stationId=sid, longitude=xy[0], latitude=xy[1])
        for sid, xy in coords.items()
    ]
    depot_sid = skel[0]
    return PlanRequest(
        requestId=f"AB-LARGE-{mode}",
        batchStart=START,
        batchEnd=END,
        depot=Station(stationId=depot_sid, longitude=coords[depot_sid][0], latitude=coords[depot_sid][1]),
        stations=[s for s in stations if s.stationId != depot_sid],
        vehicles=[Vehicle(
            vehicleId=90001, passengerCapacity=24, cargoCapacity=80,
            initialPassengerLoad=0, initialCargoLoad=40,
            cargoWeightCapacityKg=800.0, cargoVolumeCapacityM3=6.0,
            skeleton=skel,
        )],
        orders=orders,
        shipments=ships,
        algorithmConfig=AlgorithmConfig(
            algorithmMode=AlgorithmMode.HACO,
            randomSeed=seed,
            max_iterations=8,
            ant_count=24,
            maxDetourDistanceKm=3.5,
            use_branch_ranker=mode,
            # extra=allow → 大候选池
            candidate_size=candidate_size,
        ),
    ), rt["name"], len(orders) + len(ships), len(skel)


def run_once(mode: str, n_orders: int, candidate_size: int):
    req, line_name, n_tasks, n_skel = build_request(mode, n_orders, candidate_size)
    # 清理累计 stats
    C.generate_insertion_candidates.last_stats = {}
    t0 = time.perf_counter()
    out = solve(req)
    elapsed = time.perf_counter() - t0
    stats = dict(getattr(C.generate_insertion_candidates, "last_stats", {}) or {})
    rk = __import__("app.haco.ml_ranker", fromlist=["get_ranker"]).get_ranker()
    stops = sum(len(p.stops) for p in out.vehicle_plans)
    return {
        "mode": mode,
        "line": line_name,
        "tasks": n_tasks,
        "skeleton_stops": n_skel,
        "candidate_size": candidate_size,
        "status": out.status,
        "elapsed_s": round(elapsed, 3),
        "vehicles": len(out.vehicle_plans),
        "totalDistance": round(out.total_distance, 4),
        "stops": stops,
        "unassigned": list(out.unassigned_order_ids or []),
        "warnings": [w for w in (out.warnings or []) if any(k in w for k in ("MS=", "SEED", "ITER", "ML", "SEARCH"))][:10],
        "last_stats": stats,
        "ranker": {"version": rk.version, "can_rank": rk.can_rank, "rerank_calls": rk.rerank_calls},
    }


def main():
    n_orders = int(sys.argv[1]) if len(sys.argv) > 1 else 22
    candidate_size = int(sys.argv[2]) if len(sys.argv) > 2 else 64
    print("=" * 64)
    print(f"大候选池 A/B · {n_orders} 单 · candidate_size={candidate_size} · 真实公交线+真实坐标")
    print("=" * 64)
    results = []
    for mode in ("off", "force"):
        s = run_once(mode, n_orders, candidate_size)
        results.append(s)
        print(f"\n【{mode}】{s['status']}  耗时 {s['elapsed_s']}s  线路 {s['line'][:40]}")
        print(f"  任务 {s['tasks']}  骨架站 {s['skeleton_stops']}  用车 {s['vehicles']}  里程 {s['totalDistance']}  停靠 {s['stops']}")
        print(f"  未分配 {s['unassigned']}")
        print(f"  last_stats {s['last_stats']}")
        print(f"  ranker {s['ranker']}")
        print(f"  warn {s['warnings']}")

    a, b = results[0], results[1]
    print("\n" + "=" * 64)
    print("对比（force 相对 off）")
    print(f"  耗时: {a['elapsed_s']}s → {b['elapsed_s']}s  ({b['elapsed_s']-a['elapsed_s']:+.3f}s)")
    print(f"  里程: {a['totalDistance']} → {b['totalDistance']}")
    print(f"  停靠: {a['stops']} → {b['stops']}")
    print(f"  未分配: {len(a['unassigned'])} → {len(b['unassigned'])}")
    # search reduction：screened→selected 压缩率
    for s in results:
        st = s["last_stats"]
        scr = st.get("screened_count") or 0
        sel = st.get("selected_count") or 0
        ratio = (sel / scr) if scr else None
        print(f"  [{s['mode']}] search_reduction screened={scr} selected={sel} keep={ratio}")
    out_path = ROOT / "artifacts" / "ab_large_pool_result.json"
    out_path.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("saved", out_path)


if __name__ == "__main__":
    main()
