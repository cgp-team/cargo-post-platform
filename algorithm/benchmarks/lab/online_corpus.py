"""线上请求快照 → 度量台的题（任务 #33：自优化的数据地基）。

题源不是手写夹具：`benchmarks/lab/export_snapshots.py` 从 `transport_algorithm_request`
导出的 JSONL，每行就是一次真实派单发给算法的 PlanRequest 原文。

三条与生成语料不同的规矩（都写在这里，不让它们悄悄渗进 A/B 判据）：
1. **标签只能是 `verify`**：线上快照没有见证解、也没有"必须拒绝"的证明，
   所以只能问"返回的方案合不合规"，不能问"该不该可行"。
   `run.py` 的 serve/reject 分母因此天然不含它们（见 run.py 的 `verify` 桶），
   对照参照解也必须用**单独**的文件（线上集合会长新样本，和冻结参照不是一回事）。
2. **OD 用真实路网，键用坐标不用库 ID**：留痕里的 stationId 是业务库自增号，
   和公交站点 ID 同名的可能性是存在的 —— 共享一张 OD 缓存就会把两个不同地点算成同一条腿。
   这里把缓存键换成 `(经度,纬度)` 本身，跨快照同一站点仍复用，且永不与生成语料的键相撞。
3. **坐标口径**：请求里的经纬度是 GCJ-02（与高德/小程序一致，见站点表设计），
   算路要 WGS-84 ⇒ 用生产同一个转换器 `app.routing.coordinate.gcj02_to_wgs84`，
   不在这里另写一套偏移。

顺带记录一条会过期的事实（导出当轮实测，49 份快照）：orders 只有
`orderId/orderType/stationId/itemCount`，时效/重量体积/roadAccess **一个都没有**，
且 127 张单全是 DELIVERY/PICKUP（0 张客运）—— 因为这些是 2026-09 的留痕，
早于 §15 时效贯通与载重贯通（10 月）。⇒ 本文件读的是"通路"，等现网跑上当前构建之后，
新落库的快照才会带着这些字段进来；覆盖率读数见 `coverage()`。
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field

from app.models import OrderType, PlanRequest, Station
from app.routing.coordinate import gcj02_to_wgs84
from app.routing.local_routing import haversine_m

from .instances import Instance
from .roadnet import RoadNet, RealStop, SPEED_M_S

HERE = os.path.dirname(os.path.abspath(__file__))
SNAPSHOT_FILE = os.path.join(HERE, "online", "snapshots.jsonl")
# 独立命名空间：绝不与生成语料的 roadod 缓存混用（规矩 2）
OD_CACHE_NAME = "roadod-online"
ESTIMATE_FACTOR = 1.3  # roadnet.od 在图上不可达时的估算倍率，用于识别"这条腿其实是直线折算"


@dataclass
class Coverage:
    """导出集的真实字段覆盖读数 —— 决定这批题能测到哪一条通路。"""
    snapshots: int = 0
    orders: int = 0
    shipments: int = 0
    passengers: int = 0
    deliveries: int = 0
    pickups: int = 0
    order_with_timeliness: int = 0
    order_with_weight: int = 0
    order_with_road_access: int = 0
    shipment_with_timeliness: int = 0
    vehicles_with_skeleton: int = 0
    parsed_failed: int = 0
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {k: (v if not isinstance(v, list) else list(v)) for k, v in self.__dict__.items()}


def iter_raw(path: str = SNAPSHOT_FILE) -> list[dict]:
    if not os.path.exists(path):
        raise FileNotFoundError(
            f"没有线上快照文件 {path}：先跑 `python -m benchmarks.lab.export_snapshots`"
            "（凭据走 LAB_MYSQL_* 环境变量）"
        )
    out = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def _stop(lon: float, lat: float) -> RealStop:
    """请求里是 GCJ-02；算路要 WGS-84。缓存键用坐标（规矩 2）。"""
    wlon, wlat = gcj02_to_wgs84(lon, lat)
    key = f"{round(wlon, 5)},{round(wlat, 5)}"
    return RealStop(station_id=key, name=key, wgs=(wlat, wlon), gcj=(lat, lon))


def _matrix(net: RoadNet, stations: list[Station]) -> tuple[dict, int]:
    """(OD 字典, 其中"图上不可达⇒直线×1.3 估算"的腿对数)。"""
    stops = {}
    for s in stations:
        if s.longitude is None or s.latitude is None:
            raise ValueError(f"站点 {s.stationId} 没有坐标，无法重算真实路段 OD")
        stops[s.stationId] = _stop(float(s.longitude), float(s.latitude))
    m: dict[tuple[str, str], tuple[float, float]] = {}
    estimated = 0
    ids = sorted(stops)
    for a in ids:
        for b in ids:
            if a == b:
                m[(a, b)] = (0.0, 0.0)
                continue
            km, sec = net.od(stops[a], stops[b])
            m[(a, b)] = (km, sec)
            straight = haversine_m(stops[a].wgs, stops[b].wgs) / 1000.0
            if abs(km - round(straight * ESTIMATE_FACTOR, 4)) < 1e-9:
                estimated += 1
    return m, estimated // 2  # 往返成对出现，折回"腿对数"


def _tier(n_stations: int, n_tasks: int) -> str:
    if n_stations <= 6 and n_tasks <= 4:
        return "S"
    if n_stations <= 20 and n_tasks <= 14:
        return "M"
    return "L"


def _tally(req: PlanRequest, cov: Coverage) -> None:
    """登记这份快照到底覆盖了哪些通路（纯解析，不算路）。"""
    cov.snapshots += 1
    for o in req.orders:
        cov.orders += 1
        if o.orderType == OrderType.PASSENGER:
            cov.passengers += 1
        elif o.orderType == OrderType.DELIVERY:
            cov.deliveries += 1
        else:
            cov.pickups += 1
        if o.readyTime or o.pickupDeadline or o.deliveryDeadline or o.priority:
            cov.order_with_timeliness += 1
        if o.weightKg is not None or o.volumeM3 is not None:
            cov.order_with_weight += 1
        if o.roadAccess:
            cov.order_with_road_access += 1
    for s in req.shipments:
        cov.shipments += 1
        if s.readyTime or s.pickupDeadline or s.deliveryDeadline or s.priority:
            cov.shipment_with_timeliness += 1
    cov.vehicles_with_skeleton += sum(1 for v in req.vehicles if v.skeleton)


def to_instance(rec: dict, net: RoadNet, coverage: Coverage | None = None) -> Instance | None:
    """一条留痕 → 一道 verify 题；解析失败返回 None（调用方计数，不静默吞）。"""
    try:
        req = PlanRequest.model_validate(rec["request"])
    except Exception as exc:  # 契约不认的历史快照：登记，不改判据
        if coverage is not None:
            coverage.parsed_failed += 1
            coverage.notes.append(f"{rec.get('snapshot_hash')}: {type(exc).__name__}")
        return None

    if coverage is not None:
        _tally(req, coverage)

    tasks = list(req.orders) + list(req.shipments)
    matrix, estimated = _matrix(net, [req.depot] + list(req.stations))
    iid = f"online-{rec['snapshot_hash']}"

    return Instance(
        iid, "online", _tier(len(req.stations) + 1, len(tasks)),
        req, matrix, "verify", False,
        len(tasks), len(tasks),
        {
            "source": "online",
            "created_at": rec.get("created_at"),
            "db_status": rec.get("db_status"),
            "algorithm_version": rec.get("algorithm_version"),
            "n_stations": len(req.stations) + 1,
            "n_vehicles": len(req.vehicles),
            # 直线估算腿对数：非零就说明这批点位有一部分不在图覆盖内，读数要带着这个限定条件读
            "od_estimated_pairs": estimated,
            "od_pairs": len(req.stations) * (len(req.stations) + 1) // 2,
        },
    )


def _default_net() -> RoadNet:
    """每个进程只加载一次重庆图（341MB pickle，冷加载 ~10s）。

    `run.py` 的 worker 会按 instance_id 反复取题，如果每次都 new 一个 RoadNet，
    就等于每题重付一次图加载 —— 读数不变但墙钟翻倍以上。
    """
    global _NET
    if _NET is None:
        _NET = RoadNet(cache_name=OD_CACHE_NAME)
    return _NET


_NET: RoadNet | None = None


def load_by_id(iid: str) -> Instance | None:
    """按 `online-<hash12>` 取一道题：从导出原文重建，**OD 走真实路网**。

    不能借用 `instances.load()` —— 它用 `km_matrix(req.stations)` 重建矩阵（直线口径），
    那等于把线上题的真实路段距离悄悄换成直线距离：#35 记的正是这种分叉。
    """
    if not iid.startswith("online-"):
        return None
    want = iid[len("online-"):]
    net = _default_net()
    for rec in iter_raw():
        if str(rec.get("snapshot_hash", ""))[:12] == want:
            return to_instance(rec, net)
    return None


def corpus(path: str = SNAPSHOT_FILE, limit: int | None = None) -> list[Instance]:
    net = RoadNet(cache_name=OD_CACHE_NAME)
    out: list[Instance] = []
    for rec in iter_raw(path):
        if limit is not None and len(out) >= limit:
            break
        inst = to_instance(rec, net)
        if inst is not None:
            out.append(inst)
    # 落盘 OD 缓存：`run.py` 的 worker 是各自进程重新 `_load(iid)` 建题的，
    # 不落盘就等于每个 worker 把同一批腿重新算路一遍（实测单份 13 站快照冷路由 14s）。
    # roadnet.py 开头的硬规则也是这个意思：同一条 (km, s) 必须永远读缓存，跨版本才可比。
    net.save()
    return out


def coverage(path: str = SNAPSHOT_FILE) -> Coverage:
    """导出集的字段覆盖读数（不跑求解、不算路）—— 决定这批题能测哪条通路。"""
    cov = Coverage()
    for rec in iter_raw(path):
        try:
            req = PlanRequest.model_validate(rec["request"])
        except Exception as exc:
            cov.parsed_failed += 1
            cov.notes.append(f"{rec.get('snapshot_hash')}: {type(exc).__name__}")
            continue
        _tally(req, cov)
    return cov
