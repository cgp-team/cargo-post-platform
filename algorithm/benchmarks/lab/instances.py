"""参数化实例语料生成 + 可行性见证。

设计原则：
1. 契约口径优先：骨架车的乘客只能在该站 PASS 紧邻窗口上下客（见
   route_genome._skeleton_service_window_ok），因此骨架族只生成「顺向」乘客
   （上车站序号 < 下车站序号）。反向乘客在单程公交线上本就无解，不能算进
   「可行率」分母，否则度量台会把「正确拒绝」误判成「算法失败」。
2. 标签由见证背书，不由生成器自吹：expect="serve" 的实例必须能用确定性
   cheapest-insertion 构造出完整方案，并由 FeasibilityEngine **带时间窗**复核通过。
   expect="reject" 的实例必须能证明「几何上放得下、但容量/时间窗不允许」，
   即算法必须拒绝，返回任何方案都算错。
3. 需求爬坡：从基础任务数逐步加压，直到见证失败再退一档 → 得到「紧但可解」
   的实例，这才区分得出算法强弱（松实例谁都解得动）。
4. 留出集：按 instance_id 的 sha256 切分 train/val/test ≈ 60/20/20，禁止按
   候选/随机粒度切，避免同族实例同时出现在调参集与验证集造成过拟合错觉。
5. 可复现：种子只用 sha256（Python 内建 hash() 每进程加盐，会让语料漂移）。
"""

from __future__ import annotations

import hashlib
import json
import os
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

from app.distance import haversine_km
from app.haco import v14_solver as V
from app.haco.config import HacoConfig
from app.haco.deadline import SearchDeadline
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.route_genome import RouteGenome
from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    Vehicle,
)

BASE_LON, BASE_LAT = 106.50, 29.55  # 重庆主城附近，GCJ-02
KM_PER_DEG_LON = 96.4  # cos(29.55°)*111.32
KM_PER_DEG_LAT = 110.6
AVG_SPEED_KMH = 25.0

# 档位：站点数 / 车辆数 / 基础任务规模（契约上限 30 站 · 25 单 · 3 车）
TIERS: dict[str, tuple[int, int, int]] = {
    "S": (6, 1, 5),
    "M": (12, 2, 12),
    "L": (22, 3, 20),
}

FAMILIES = ("vrp", "corridor", "village", "multidepot", "cap_tight", "tw_tight")
# 契约规模上限：站点 ≤30 / 订单 ≤25 / 车辆 ≤3（超过则 API 直接 OVER_LIMIT，不进搜索）
MAX_STATIONS = 30
MAX_BLOCKS = 24


def _stable_seed(text: str) -> int:
    return int(hashlib.sha256(text.encode()).hexdigest()[:8], 16)


def _pt(km_x: float, km_y: float, sid: str) -> Station:
    return Station(
        stationId=sid,
        longitude=round(BASE_LON + km_x / KM_PER_DEG_LON, 6),
        latitude=round(BASE_LAT + km_y / KM_PER_DEG_LAT, 6),
    )


def km_matrix(stations: list[Station]) -> dict:
    """站点对 → (公里, 估算秒)。用 Haversine 公里替代「度」，
    这样 max_detour_km=2.0 这类以公里计的硬约束才真正生效。"""
    m: dict[tuple[str, str], tuple[float, float]] = {}
    for a in stations:
        for b in stations:
            if a.stationId == b.stationId:
                m[(a.stationId, b.stationId)] = (0.0, 0.0)
                continue
            km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
            m[(a.stationId, b.stationId)] = (km, round(km / AVG_SPEED_KMH * 3600.0, 1))
    return m


@dataclass
class Instance:
    instance_id: str
    family: str
    tier: str
    request: PlanRequest
    matrix: dict
    expect: str              # "serve" 必须给出方案 | "reject" 必须拒绝(有证明) | "verify" 只看方案是否合规
    witness_feasible: bool   # expect="serve" 时由带时间窗的构造见证背书
    n_tasks: int
    n_orders: int
    meta: dict = field(default_factory=dict)

    @property
    def split(self) -> str:
        return split(self.instance_id)


def _layout(family: str, tier: str, rng: random.Random) -> list[Station]:
    n_stations = TIERS[tier][0]
    depot = _pt(0.0, 0.0, "D")
    stations = [depot]
    if family in ("corridor", "village", "multidepot"):
        x = 0.0
        for i in range(1, n_stations + 1):
            x += rng.uniform(0.9, 2.6)
            stations.append(_pt(x, rng.uniform(-0.5, 0.5), f"S{i}"))
    else:
        for i in range(1, n_stations + 1):
            stations.append(
                _pt(rng.uniform(0.4, 9.0), rng.uniform(-4.5, 4.5), f"S{i}")
            )
    return stations


def _vehicles(family: str, tier: str, stations: list[Station], rng: random.Random) -> list[Vehicle]:
    ids = [s.stationId for s in stations if s.stationId != "D"]
    out = []
    for i in range(TIERS[tier][1]):
        skel = list(ids) if family in ("corridor", "village", "multidepot") else None
        v = Vehicle(
            vehicleId=1000 + i,
            passengerCapacity=rng.choice([6, 8, 10]),
            cargoCapacity=rng.choice([6, 8, 12]),
            skeleton=skel,
        )
        if family == "multidepot":
            v.startStationId = ids[0]
            v.endStationId = ids[-1]
        out.append(v)
    return out


def _orders(
    family: str, tier: str, stations: list[Station], vehicles: list[Vehicle],
    n_tasks: int, rng: random.Random,
) -> tuple[list[PlanOrder], list[PlanShipment]]:
    ids = [s.stationId for s in stations if s.stationId != "D"]
    skeleton = vehicles[0].skeleton
    orders: list[PlanOrder] = []
    shipments: list[PlanShipment] = []
    n_pax = max(1, int(n_tasks * rng.uniform(0.35, 0.55)))
    n_del = max(1, int(n_tasks * 0.2))
    n_pick = max(1, int(n_tasks * 0.2))
    n_ship = max(0, n_tasks - n_pax - n_del - n_pick)
    village_seq = 0

    for i in range(n_pax):
        if skeleton:
            a = rng.randrange(len(skeleton) - 1)
            b = rng.randrange(a + 1, len(skeleton))  # 只生成顺向乘客
            board, alight = skeleton[a], skeleton[b]
        else:
            board, alight = rng.sample(ids, 2)
        orders.append(PlanOrder(
            orderId=f"P{i + 1}", orderType=OrderType.PASSENGER,
            boardingStationId=board, alightingStationId=alight,
        ))
    for i in range(n_del):
        if family == "village" and rng.random() < 0.45:
            village_seq += 1
            sid = f"V{village_seq}"
        elif skeleton and rng.random() < 0.7:
            sid = rng.choice(skeleton)
        else:
            sid = rng.choice(ids)
        orders.append(PlanOrder(
            orderId=f"D{i + 1}", orderType=OrderType.DELIVERY,
            stationId=sid, itemCount=rng.choice([1, 1, 2]),
        ))
    for i in range(n_pick):
        if family == "village" and rng.random() < 0.45:
            village_seq += 1
            sid = f"V{village_seq}"
        elif skeleton and rng.random() < 0.7:
            sid = rng.choice(skeleton)
        else:
            sid = rng.choice(ids)
        orders.append(PlanOrder(
            orderId=f"K{i + 1}", orderType=OrderType.PICKUP,
            stationId=sid, itemCount=rng.choice([1, 1, 2]),
        ))
    for i in range(n_ship):
        pk, dl = (rng.choice(skeleton), rng.choice(skeleton)) if skeleton else rng.sample(ids, 2)
        if pk == dl:
            continue
        shipments.append(PlanShipment(
            shipmentId=f"T{i + 1}", pickupStationId=pk, deliveryStationId=dl,
            quantity=rng.choice([1, 2]),
        ))
    return orders, shipments


def _attach_villages(stations: list[Station], orders: list[PlanOrder], rng: random.Random) -> list[Station]:
    """village 族：给 V 点挂一个离线村点（距锚站 0.4~3.6km，横跨 2km 绕行阈值）。"""
    anchors = [s for s in stations if s.stationId != "D"]
    out = []
    for o in orders:
        if not (o.stationId or "").startswith("V"):
            continue
        anchor = rng.choice(anchors)
        ax = (anchor.longitude - BASE_LON) * KM_PER_DEG_LON
        ay = (anchor.latitude - BASE_LAT) * KM_PER_DEG_LAT
        out.append(_pt(ax, ay + rng.uniform(0.4, 3.6), o.stationId))
    return out


def _request(
    iid: str, family: str, stations: list[Station], vehicles: list[Vehicle],
    orders: list[PlanOrder], shipments: list[PlanShipment], seed: int, window_min: int,
) -> PlanRequest:
    start = datetime(2026, 10, 1, 8, 0, tzinfo=timezone.utc)
    cfg = AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=seed)
    return PlanRequest(
        requestId=iid, batchStart=start, batchEnd=start + timedelta(minutes=window_min),
        depot=stations[0], stations=stations, vehicles=vehicles,
        orders=orders, shipments=shipments, algorithmConfig=cfg,
    )


def witness(request: PlanRequest, matrix: dict, use_window: bool = True) -> tuple[bool, str]:
    """确定性 cheapest-insertion 构造完整方案 + FeasibilityEngine 复核 → 可行性见证。

    use_window=True 时带上 batchStart~batchEnd 的时间窗，与求解器同一把尺子。
    """
    cfg = HacoConfig()
    smap = {s.stationId: s for s in request.stations}
    smap[request.depot.stationId] = request.depot
    tasks = sorted(V._encode_tasks(request), key=V._canonical_order)
    if not tasks:
        return False, "NO_TASKS"
    templates = [
        RouteGenome(
            vehicle_index=i, vehicle_id=v.vehicleId,
            depot_station=v.startStationId or request.depot.stationId,
            skeleton=list(v.skeleton) if v.skeleton else None,
            end_station=v.endStationId or request.depot.stationId,
        )
        for i, v in enumerate(request.vehicles)
    ]
    pc = {i: v.passengerCapacity for i, v in enumerate(request.vehicles)}
    cc = {i: v.cargoCapacity for i, v in enumerate(request.vehicles)}
    ipl = {i: v.initialPassengerLoad for i, v in enumerate(request.vehicles)}
    icl = {i: v.initialCargoLoad for i, v in enumerate(request.vehicles)}
    max_duration = None
    if use_window:
        max_duration = float(request.batchEnd.timestamp() - request.batchStart.timestamp())
    # 逐单时效用同一把尺子：语料里只要有人给了 readyTime/截止时刻，见证器就必须按
    # 同一起点核时效，否则「见证得到可行解」这个标签会和求解器的判定口径分叉。
    has_eta = any(
        getattr(s, "readyTime", None) is not None
        or getattr(s, "pickupDeadline", None) is not None
        or getattr(s, "deliveryDeadline", None) is not None
        for s in request.shipments
    )
    engine = FeasibilityEngine(
        station_map=smap, matrix=matrix, max_duration=max_duration,
        base_time_s=(request.batchStart.timestamp() if has_eta else None),
    )
    tmap = {t.task_id: t for t in tasks}
    res = V._cheapest_insertion(
        tasks, templates, tmap, engine, pc, cc, ipl, icl, smap, matrix,
        SearchDeadline.from_seconds(25.0),
    )
    if not res.complete:
        return False, f"PLACE_FAILED({res.placed_count}/{res.total_tasks})"
    ok = V._routes_feasible(res.routes, tmap, engine, pc, cc, ipl, icl, smap, matrix)
    return (True, "WITNESSED") if ok else (False, "ENGINE_REJECTED")


def _assemble(family: str, tier: str, n_tasks: int, seed: int, window_min: int):
    rng = random.Random(seed)
    stations = _layout(family, tier, rng)
    orders, shipments = _orders(family, tier, stations, _vehicles(family, tier, stations, rng), n_tasks, rng)
    if family == "village":
        stations = stations + _attach_villages(stations, orders, rng)
    vehicles = _vehicles(family, tier, stations, rng)
    return _request(f"{family}-{tier}", family, stations, vehicles, orders, shipments, seed, window_min)


def build(family: str, tier: str, index: int, ramp: int = 5) -> Instance | None:
    """生成一个实例。serve 族爬坡到「紧但可解」；reject 族造「必须拒绝」样本。

    返回 None 表示该格造不出合规样本（调用方跳过，不塞脏数据进指标）。
    """
    iid = f"{family}-{tier}-{index:03d}"
    base = TIERS[tier][2]

    if family == "cap_tight":
        # 「必须拒绝」要有算术证明：所有揽收件都得在同一趟返程里搬回场站，
        # 因此 Σ PICKUP.itemCount > Σ cargoCapacity 是硬不可行。
        # 注意不能用「派送+揽收总和」：货仓是出程派送、返程揽收依次复用的，
        # 总和超运力并不代表不可行（solver 的 precheck 也是分向比较的）。
        for step in range(ramp):
            req = _assemble("vrp", tier, base + step, _stable_seed(iid) + step, 260)
            if len(req.orders) + len(req.shipments) >= MAX_BLOCKS:
                continue
            req.requestId = iid
            cap = sum(v.cargoCapacity for v in req.vehicles)
            pickups = sum(o.itemCount for o in req.orders if o.orderType == OrderType.PICKUP)
            if pickups > cap:
                extra = 0
            else:
                extra = cap - pickups + 1
            req.orders.append(PlanOrder(
                orderId="XB1", orderType=OrderType.PICKUP,
                stationId=[s.stationId for s in req.stations if s.stationId != "D"][0],
                itemCount=1 + extra,
            ))
            pickups += 1 + extra
            return Instance(iid, family, tier, req, km_matrix(req.stations),
                            "reject", False, base + step,
                            len(req.orders) + len(req.shipments),
                            {"why": "PICKUP_DEMAND_OVER_CAPACITY",
                             "pickup_demand": pickups, "cargo_cap": cap})
        return None

    if family == "tw_tight":
        for step in range(ramp):
            wide = _assemble("corridor", tier, base + step, _stable_seed(iid) + step, 400)
            wide.requestId = iid
            ok, why = witness(wide, km_matrix(wide.stations), use_window=True)
            if not ok:
                continue
            tight = _assemble("corridor", tier, base + step, _stable_seed(iid) + step, 6)
            tight.requestId = iid
            nok, nwhy = witness(tight, km_matrix(tight.stations), use_window=True)
            if nok:
                continue  # 窗口还是太松，不构成压力样本
            # 注意：cheapest-insertion 放不下 ≠ 证明无解（它只是构造启发式）。
            # 所以 tw_tight 标 "verify"：不要求状态，但返回的方案必须过独立校验，
            # 且若判无解要给出正当原因码 —— 用来抓「为了显得可行而塞违规方案」。
            return Instance(iid, family, tier, tight, km_matrix(tight.stations),
                            "verify", False, base + step,
                            len(tight.orders) + len(tight.shipments),
                            {"why": "WINDOW_TIGHT_UNPROVEN", "loose": why, "tight": nwhy})
        return None

    last_ok = None
    for step in range(ramp):
        n_tasks = min(base + step * max(1, base // 3), MAX_BLOCKS)
        req = _assemble(family, tier, n_tasks, _stable_seed(iid) + step * 7919, 260)
        req.requestId = iid
        if len(req.orders) + len(req.shipments) > MAX_BLOCKS or len(req.stations) > MAX_STATIONS + 1:
            break
        mat = km_matrix(req.stations)
        ok, why = witness(req, mat, use_window=True)
        if ok:
            last_ok = Instance(iid, family, tier, req, mat, "serve", True, n_tasks,
                               len(req.orders) + len(req.shipments),
                               {"why": why, "ramp_step": step})
            if n_tasks == MAX_BLOCKS:
                break
        else:
            break  # 紧到放不下就停，保留上一档「可解」样本
    if last_ok is not None:
        return last_ok
    # 基础档就放不开：不是「应拒绝」的证明样本，直接丢弃，
    # 避免把「造例器自己没造出解」当成「算法应该判无解」来奖励。
    return None


def dump(inst: Instance) -> None:
    path = os.path.join(os.path.dirname(__file__), "cache")
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, f"{inst.instance_id}.json"), "w", encoding="utf-8") as fh:
        json.dump({
            "instance_id": inst.instance_id, "family": inst.family, "tier": inst.tier,
            "expect": inst.expect, "witness_feasible": inst.witness_feasible,
            "n_tasks": inst.n_tasks, "n_orders": inst.n_orders, "meta": inst.meta,
            "request": inst.request.model_dump(mode="json"),
        }, fh, ensure_ascii=False)


def load(iid: str) -> Instance | None:
    p = os.path.join(os.path.dirname(__file__), "cache", f"{iid}.json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as fh:
        d = json.load(fh)
    req = PlanRequest.model_validate(d["request"])
    return Instance(d["instance_id"], d["family"], d["tier"], req,
                    km_matrix(req.stations), d["expect"], d["witness_feasible"],
                    d["n_tasks"], d["n_orders"], d.get("meta", {}))


def split(instance_id: str) -> str:
    """按 instance_id 的 sha256 稳定切分 train/val/test ≈ 60/20/20。"""
    r = _stable_seed("split:" + instance_id) % 100
    return "train" if r < 60 else ("val" if r < 80 else "test")


def corpus(families: tuple[str, ...] = FAMILIES, tiers: tuple[str, ...] = ("S", "M", "L"),
           per_cell: int = 6, ramp: int = 4, persist: bool = True,
           use_cache: bool = True) -> list[Instance]:
    out = []
    for f in families:
        for t in tiers:
            for i in range(per_cell):
                iid = f"{f}-{t}-{i:03d}"
                if use_cache:
                    hit = load(iid)
                    if hit is not None:
                        out.append(hit)
                        continue
                inst = build(f, t, i, ramp=ramp)
                if inst is None:
                    continue
                if persist:
                    dump(inst)
                out.append(inst)
    return out
