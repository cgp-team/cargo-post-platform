"""真实路网语料：全部由重庆真实公交线 + 真实站点 + 真实路段算路构成。

与 instances.py（合成几何）的分工：
- 合成族保留做「几何无关」的回归对照；
- 本族是主判据 —— 站点是真实站、骨架是真实线路按序站序、里程与时长是
  OSM 路段 A* 的结果、绕行阈值 2km 在这里才有真实约束力。

不凭空造数的具体做法：
- 骨架 = 某条真实线路的一段连续按序停靠站（真实运营区段），不重排、不改坐标；
- 村点/偏远收货点 = **另一条真实线路上的真实站**（真实存在的地名与坐标），
  它与锚站的绕行距离由 A* 真实算出，天然横跨 2km 阈值两侧；
- 场站 = 该真实线路的端点站（真实客运站/起讫点语义）；
- 时效 = batchStart + 该订单起讫点的真实路网行驶时间 × 系数（系数可紧可松），
  因此「应拒绝」样本是被真实路况逼出来的，不是拍脑袋写死的时间窗。
"""

from __future__ import annotations

import hashlib
import json
import os
import random
from datetime import datetime, timedelta, timezone

from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    StopAction,
    Vehicle,
)
from app.validators import service_duration

from .instances import Instance, witness
from .roadnet import RoadNet, RealLine, RealStop

BATCH_START = datetime(2026, 10, 8, 8, 0, tzinfo=timezone.utc)

# 容量口径来源：app/models.py 契约默认（passengerCapacity=5 / cargoCapacity=4），
# 以及 haco/config.py max_detour_km=2.0；这里只在契约允许的取值范围内变化。
PAX_CAPS = (5, 8, 10)
CARGO_CAPS = (4, 8, 12)

TIER_STOPS = {"S": (6, 8), "M": (10, 14), "L": (16, 22)}
TIER_TASKS = {"S": 5, "M": 12, "L": 20}

FAMILIES = ("r_line", "r_village", "r_vrp", "r_multidepot", "r_cap_tight", "r_deadline")


def _sid(stop: RealStop) -> str:
    return stop.station_id.replace(":", "_")


def _station(stop: RealStop) -> Station:
    lat, lon = stop.gcj
    return Station(stationId=_sid(stop), longitude=round(lon, 6), latitude=round(lat, 6))


def _stable(text: str) -> int:
    return int(hashlib.sha256(text.encode()).hexdigest()[:8], 16)


def _pick_line(net: RoadNet, lines: list[RealLine], need: int, rng: random.Random) -> RealLine:
    cands = [l for l in lines if len(l.stops) >= need]
    return rng.choice(cands)


def _neighbor_stops(net: RoadNet, lines: list[RealLine], anchors: list[RealStop],
                   rng: random.Random, max_km: float) -> list[RealStop]:
    """取真实邻近站：其它线路上的真实站，且与锚站直线距离在 max_km 内。"""
    from app.routing.local_routing import haversine_m
    anchor_ids = {a.station_id for a in anchors}
    out: list[RealStop] = []
    pool = [s for l in lines for s in l.stops if s.station_id not in anchor_ids]
    rng.shuffle(pool)
    for s in pool:
        if len(out) >= 8:
            break
        for a in anchors:
            if haversine_m(a.wgs, s.wgs) / 1000.0 <= max_km:
                out.append(s)
                break
    return out


def _orders(skeleton: list[RealStop], offsite: list[RealStop], n_tasks: int,
            rng: random.Random, use_offsite: bool,
            has_line: bool, net: RoadNet | None = None,
            family: str = "r_line") -> tuple[list[PlanOrder], list[PlanShipment]]:
    orders: list[PlanOrder] = []
    shipments: list[PlanShipment] = []
    n_del = max(1, int(n_tasks * 0.2))
    n_pick = max(1, int(n_tasks * 0.2))
    if has_line:
        n_pax = max(1, int(n_tasks * rng.uniform(0.35, 0.5)))
    else:
        # 业务形态约束：公交必须按线路每站必停拉客，项目里没有「网上预约乘车」，
        # 所以乘客只能出现在有骨架（真实线路）的车上。无骨架车是纯货运调度，
        # 不能给它造散客订单 —— 那会测试一个现实中不存在的服务，指标也就失去意义。
        n_pax = 0
    n_ship = max(0, n_tasks - n_pax - n_del - n_pick)

    for i in range(n_pax):
        a = rng.randrange(len(skeleton) - 1)
        b = rng.randrange(a + 1, len(skeleton))   # 真实线路只跑单向：只生成顺向乘客
        orders.append(PlanOrder(
            orderId=f"P{i + 1}", orderType=OrderType.PASSENGER,
            boardingStationId=_sid(skeleton[a]), alightingStationId=_sid(skeleton[b]),
        ))
    for i in range(n_del):
        if use_offsite and offsite and rng.random() < 0.5:
            sid = _sid(offsite[i % len(offsite)])
        else:
            sid = _sid(rng.choice(skeleton[1:]))
        orders.append(PlanOrder(
            orderId=f"D{i + 1}", orderType=OrderType.DELIVERY,
            stationId=sid, itemCount=rng.choice([1, 1, 2]),
        ))
    for i in range(n_pick):
        if use_offsite and offsite and rng.random() < 0.5:
            sid = _sid(offsite[(i + 4) % len(offsite)])
        else:
            sid = _sid(rng.choice(skeleton[1:]))
        orders.append(PlanOrder(
            orderId=f"K{i + 1}", orderType=OrderType.PICKUP,
            stationId=sid, itemCount=rng.choice([1, 1, 2]),
        ))
    for i in range(n_ship):
        pk = rng.choice(skeleton[1:])
        dl = rng.choice(skeleton[1:])
        if pk.station_id == dl.station_id:
            continue
        eta = _timeliness(net, skeleton, pk, dl, family, rng)
        shipments.append(PlanShipment(
            shipmentId=f"T{i + 1}", pickupStationId=_sid(pk), deliveryStationId=_sid(dl),
            quantity=rng.choice([1, 2]), **eta,
        ))
    return orders, shipments


PASS_SEC = float(service_duration(StopAction.PASS))
PICK_SEC = float(service_duration(StopAction.PICKUP))
DLV_SEC = float(service_duration(StopAction.DELIVER))


def _timeliness(net, skeleton: list[RealStop], pk: RealStop, dl: RealStop,
                family: str, rng: random.Random) -> dict:
    """逐单时效：batchStart + 该单起讫点的**真实路网行驶时间**× 系数（模块开头口径）。

    下界 floor 不是两点直线，而是「这班车按骨架每站必停开到送货站」的真实耗时：
      max(首站→送货站, 首站→揽收站 + 揽收站→送货站)   ← OSM A* 的真实秒数
      + 每站必停 30s × 途中停靠次数                    ← 业务既定动作
      + 揽收 60s + 派送 60s                            ← validators 的服务时间
    系数就是「给不给余量」：
      - 常规族在 1.15 / 1.5 / 2.0 之间抽（有紧有松，贴近真实承诺差异）；
      - `r_deadline` 族 70% 抽 1.0（必须一分不差按最短路径走才赶得上）、
        30% 抽 0.85（比物理下界还短 → **可证明带不动**，考察算法是否如实舍弃而不是硬塞迟到）。
    readyTime 按真实揽件节奏撒：多数无影响，少数让货在车到之前还没打包好
    （逼迫等待或改派），比例写死在这里便于复现。
    priority 按「普通件为主、少量加急」分布（契约字段 int ≥ 0）。
    """
    if net is None:
        return {}
    k_pk, k_dl = skeleton.index(pk), skeleton.index(dl)
    direct_dl = net.od(skeleton[0], dl)[1]
    via_pk = net.od(skeleton[0], pk)[1] + net.od(pk, dl)[1]
    floor_dl = max(direct_dl, via_pk) + PASS_SEC * k_dl + PICK_SEC + DLV_SEC
    floor_pk = net.od(skeleton[0], pk)[1] + PASS_SEC * k_pk + PICK_SEC

    if family == "r_deadline":
        factor = 1.0 if rng.random() < 0.7 else 0.85
    else:
        factor = rng.choice((1.15, 1.5, 1.5, 2.0))

    out = {
        "deliveryDeadline": BATCH_START + timedelta(seconds=floor_dl * factor),
        "priority": rng.choice((0, 0, 0, 1, 3)),
    }
    # 揽收截止只对一半的单子显式承诺（另一半只承诺送达）：
    # 契约里这些字段本来全是 optional，语料必须同时覆盖「给了」和「没给」两种形态。
    if rng.random() < 0.5:
        out["pickupDeadline"] = BATCH_START + timedelta(seconds=floor_pk * factor)
    r = rng.random()
    if r < 0.08:
        # 货在车完成本单之前还没打包好：等待会推后送达，多数情况下这单就该被舍弃
        out["readyTime"] = BATCH_START + timedelta(seconds=floor_pk * 0.9)
    elif r < 0.33:
        out["readyTime"] = BATCH_START + timedelta(seconds=floor_pk * 0.4)
    return out


def _request(iid: str, stations: list[Station], vehicles: list[Vehicle],
             orders: list[PlanOrder], shipments: list[PlanShipment],
             window_min: int, seed: int) -> PlanRequest:
    return PlanRequest(
        requestId=iid, batchStart=BATCH_START,
        batchEnd=BATCH_START + timedelta(minutes=window_min),
        depot=stations[0], stations=stations, vehicles=vehicles,
        orders=orders, shipments=shipments,
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=seed),
    )


def _matrix(net: RoadNet, pts: list[RealStop]) -> dict:
    """真实路段 OD，键用净化后的 stationId（必须与 PlanRequest 里的 id 同一套）。"""
    m: dict[tuple[str, str], tuple[float, float]] = {}
    for a in pts:
        for b in pts:
            m[(_sid(a), _sid(b))] = (0.0, 0.0) if a.station_id == b.station_id else net.od(a, b)
    return m


def _make(family: str, tier: str, index: int, net: RoadNet, lines: list[RealLine],
          window_min: int, ramp_step: int):
    seed = _stable(f"{family}-{tier}-{index}-{ramp_step}")
    rng = random.Random(seed)
    lo, hi = TIER_STOPS[tier]
    need = rng.randint(lo, hi)
    line = _pick_line(net, lines, need, rng)
    take = rng.randint(0, max(0, len(line.stops) - need))
    seg = list(line.stops)[take:take + need]
    n_tasks = min(TIER_TASKS[tier] + ramp_step * max(1, TIER_TASKS[tier] // 4), 24)

    offsite: list[RealStop] = []
    if family in ("r_village",):
        offsite = _neighbor_stops(net, lines, seg, rng, max_km=6.0)

    skeleton = seg if family != "r_vrp" else None
    vehicles = []
    n_veh = {"S": 1, "M": 2, "L": 3}[tier]
    for i in range(n_veh):
        skel = [_sid(s) for s in seg[1:]] if skeleton else None
        v = Vehicle(vehicleId=1000 + i, passengerCapacity=rng.choice(PAX_CAPS),
                    cargoCapacity=rng.choice(CARGO_CAPS), skeleton=skel)
        if family == "r_multidepot":
            v.startStationId = _sid(seg[0])
            v.endStationId = _sid(seg[-1])
        vehicles.append(v)

    pts = list(seg) + offsite
    orders, shipments = _orders(seg, offsite, n_tasks, rng,
                                use_offsite=bool(offsite), has_line=skeleton is not None,
                                net=net, family=family)
    if family == "r_cap_tight":
        cap = sum(v.cargoCapacity for v in vehicles)
        pk = sum(o.itemCount for o in orders if o.orderType == OrderType.PICKUP)
        orders.append(PlanOrder(orderId="XB1", orderType=OrderType.PICKUP,
                                stationId=_sid(seg[len(seg) // 2]),
                                itemCount=max(1, cap - pk + 2)))
    if len(orders) + len(shipments) > 25:
        return None
    stations = [_station(p) for p in pts]
    seen, uniq = set(), []
    for s in stations:
        if s.stationId in seen:
            continue
        seen.add(s.stationId)
        uniq.append(s)
    req = _request(f"{family}-{tier}", uniq, vehicles, orders, shipments, window_min, seed)
    matrix = _matrix(net, pts)
    return req, matrix, {"line": line.name, "line_id": line.route_id, "seg_stops": len(seg),
                         "offsite": [s.name for s in offsite], "n_tasks": n_tasks}


def _provably_late(req, matrix) -> list[str]:
    """用求解器**同一个**下界函数证明：这批里有没有物理上不可能按时的运单。

    标签不能靠语料生成器自己拍系数 —— 只有和被测算法同源的下界判定，
    才配叫「可证明不可全量服务」。
    """
    from app.haco.route_genome import RouteGenome
    from app.haco.v14_solver import _encode_tasks, _split_provably_late

    smap = {s.stationId: s for s in req.stations}
    smap[req.depot.stationId] = req.depot
    templates = [
        RouteGenome(
            vehicle_index=i, vehicle_id=v.vehicleId,
            depot_station=v.startStationId or req.depot.stationId,
            skeleton=list(v.skeleton) if v.skeleton else None,
            end_station=v.endStationId or req.depot.stationId,
        )
        for i, v in enumerate(req.vehicles)
    ]
    late, _ = _split_provably_late(
        _encode_tasks(req), templates, smap, matrix, req.batchStart.timestamp()
    )
    return sorted({oid for t in late for oid in t.order_ids})


def build(family: str, tier: str, index: int, net: RoadNet, lines: list[RealLine],
          ramp: int = 4) -> Instance | None:
    iid = f"{family}-{tier}-{index:03d}"
    # 批窗统一 240 分钟：真实公交线跑完一段就要几十分钟，
    # 旧口径给 r_deadline 压 8 分钟批窗是「凭空造数」（现实中不存在这种班次），
    # 现在紧度改由**逐单承诺时刻**表达（见 _timeliness），既有意义也可核验。
    window_min = 240
    last_ok = None
    for step in range(ramp):
        made = _make(family, tier, index, net, lines, window_min, step)
        if made is None:
            continue
        req, matrix, meta = made
        req.requestId = iid
        if family == "r_cap_tight":
            cap = sum(v.cargoCapacity for v in req.vehicles)
            pk = sum(o.itemCount for o in req.orders if o.orderType == OrderType.PICKUP) \
                + sum(s.quantity for s in req.shipments)
            assert pk > cap, "r_cap_tight 必须可证明超载"
            return Instance(iid, family, tier, req, matrix, "reject", False,
                            meta["n_tasks"], len(req.orders) + len(req.shipments),
                            {"why": "PICKUP_OVER_CAPACITY", "pickup": pk, "cap": cap, **meta})
        if family == "r_deadline":
            late = _provably_late(req, matrix)
            if late:
                return Instance(iid, family, tier, req, matrix, "reject", False,
                                meta["n_tasks"], len(req.orders) + len(req.shipments),
                                {"why": "ETA_PROVABLY_LATE", "late_orders": late, **meta})
        ok, why = witness(req, matrix, use_window=True)
        if family == "r_deadline":
            if not ok:
                return Instance(iid, family, tier, req, matrix, "verify", False,
                                meta["n_tasks"], len(req.orders) + len(req.shipments),
                                {"why": "WINDOW_TIGHT_UNPROVEN", "witness": why, **meta})
            continue
        if ok:
            last_ok = Instance(iid, family, tier, req, matrix, "serve", True,
                               meta["n_tasks"], len(req.orders) + len(req.shipments),
                               {"why": why, "ramp_step": step, **meta})
            continue
        break
    return last_ok


def build_corpus(families=FAMILIES, tiers=("S", "M", "L"), per_cell: int = 4,
                 ramp: int = 4) -> list[Instance]:
    net = RoadNet()
    lines = net.lines()
    out = []
    for f in families:
        for t in tiers:
            for i in range(per_cell):
                inst = build(f, t, i, net, lines, ramp=ramp)
                if inst is None:
                    continue
                dump(inst)
                out.append(inst)
    net.save()
    return out


def random_corpus(n: int = 60, epoch: int = 1, ramp: int = 4,
                  families: tuple[str, ...] = FAMILIES) -> list[Instance]:
    """现造一批真实实例（每轮换种子），防止算法被调到「只会做这一套题」。

    固定语料（cache/real_*.json）只能证明「改动没有把已知实例做坏」；
    要证明没有过拟合，必须在**没见过**的真实线路抽样上量同一条指标。
    每批都落盘，出问题时可以逐例复现同一批题。
    """
    net = RoadNet()
    lines = net.lines()
    rng = random.Random(_stable(f"epoch:{epoch}"))
    out: list[Instance] = []
    tried = 0
    while len(out) < n and tried < n * 4:
        tried += 1
        fam = rng.choice(families)
        tier = rng.choice(("S", "M", "L"))
        idx = tried
        iid = f"{fam}-{tier}-{epoch:02d}x{idx:03d}"
        base = _build_into(fam, tier, idx, epoch, net, lines, ramp)
        if base is None:
            continue
        base.instance_id = iid
        dump(base)
        out.append(base)
    net.save()
    return out


def _build_into(family: str, tier: str, index: int, epoch: int,
                net: RoadNet, lines: list[RealLine], ramp: int) -> Instance | None:
    """按 (family,tier,index,epoch) 造一个实例；epoch 进种子 → 每轮都是新题。"""
    real_index = index + epoch * 10_000
    return build(family, tier, real_index, net, lines, ramp=ramp)


def dump(inst: Instance) -> None:
    path = os.path.join(os.path.dirname(__file__), "cache")
    os.makedirs(path, exist_ok=True)
    with open(os.path.join(path, f"real_{inst.instance_id}.json"), "w", encoding="utf-8") as fh:
        json.dump({"instance_id": inst.instance_id, "family": inst.family, "tier": inst.tier,
                   "expect": inst.expect, "witness_feasible": inst.witness_feasible,
                   "n_tasks": inst.n_tasks, "n_orders": inst.n_orders, "meta": inst.meta,
                   "request": inst.request.model_dump(mode="json"),
                   "matrix": {f"{a}|{b}": [v[0], v[1]] for (a, b), v in inst.matrix.items()}},
                  fh, ensure_ascii=False)


def load(iid: str) -> Instance | None:
    p = os.path.join(os.path.dirname(__file__), "cache", f"real_{iid}.json")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as fh:
        d = json.load(fh)
    req = PlanRequest.model_validate(d["request"])
    matrix = {}
    for k, v in d["matrix"].items():
        a, b = k.split("|")
        matrix[(a, b)] = (v[0], v[1])
    return Instance(d["instance_id"], d["family"], d["tier"], req, matrix,
                    d["expect"], d["witness_feasible"], d["n_tasks"], d["n_orders"],
                    d.get("meta", {}))
