"""Transit 仓库：Snapshot / 去重 / 方向保留 / Station→RoadGraph snap。"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Sequence

from app.routing.local_routing import RoadGraph, haversine_m
from .osm_transit_discovery import TransitRoute, TransitStation
from .region import TransitRegionConfig


@dataclass
class StationRoadMatch:
    station_id: str
    nearest_node_id: str | None
    snap_distance_m: float
    graph_version: str
    status: str  # OK / STATION_SNAP_FAILED / STATION_SNAP_SUSPICIOUS


@dataclass
class TransitSnapshot:
    snapshot_version: str
    created_at: float
    source: str
    stations: list[TransitStation] = field(default_factory=list)
    routes: list[TransitRoute] = field(default_factory=list)
    route_stops: list[dict] = field(default_factory=list)
    counts: dict = field(default_factory=dict)


class TransitStationDeduplicator:
    """优先 externalId；其次 coordinate+normalized name。禁止只按 name。"""

    def dedup(self, stations: Sequence[TransitStation]) -> tuple[list[TransitStation], int]:
        by_ext: dict[str, TransitStation] = {}
        for s in stations:
            key = s.external_id or s.station_id
            if key not in by_ext:
                by_ext[key] = s
        # coordinate + name 合并
        by_xy: dict[tuple, TransitStation] = {}
        out: list[TransitStation] = []
        merged = 0
        for s in by_ext.values():
            nk = (round(s.latitude, 4), round(s.longitude, 4), s.name.strip().lower())
            if nk in by_xy:
                canon = by_xy[nk]
                for rid in s.route_ids:
                    if rid not in canon.route_ids:
                        canon.route_ids.append(rid)
                merged += 1
                continue
            by_xy[nk] = s
            out.append(s)
        return out, merged


class StationRoadSnapper:
    def snap_all(
        self, stations: Sequence[TransitStation], graph: RoadGraph, graph_version: str
    ) -> list[StationRoadMatch]:
        out = []
        for s in stations:
            nid, d = graph.nearest_node((s.latitude, s.longitude), max_snap_m=2000.0)
            if nid is None:
                status = "STATION_SNAP_FAILED"
            elif d > 500.0:
                status = "STATION_SNAP_SUSPICIOUS"
            else:
                status = "OK"
            out.append(StationRoadMatch(s.station_id, nid, d, graph_version, status))
        return out


class TransitSnapshotStore:
    """algorithm/data/transit/snapshots/ 本地冻结；训练期间 REFRESH_TRANSIT_DATA=false。"""

    def __init__(self, root: str | Path = "algorithm/data/transit"):
        self.root = Path(root)
        self.snap_dir = self.root / "snapshots"
        self.snap_dir.mkdir(parents=True, exist_ok=True)

    def save(
        self,
        region_id: str,
        stations: Sequence[TransitStation],
        routes: Sequence[TransitRoute],
        route_stops: Sequence[dict],
        *,
        source: str = "osm",
        graph_version: str = "",
        snapshot_version: str | None = None,
    ) -> TransitSnapshot:
        deduped, merged = TransitStationDeduplicator().dedup(stations)
        # 保留 direction / sequence
        stops = sorted(route_stops, key=lambda x: (x["route_id"], x.get("direction", ""), x.get("sequence", 0)))
        counts = {
            "routeCount": len(routes),
            "stationCount": len(stations),
            "routeStopCount": len(stops),
            "uniqueStationCount": len(deduped),
            "directionCount": len({(r.route_id, r.direction) for r in routes}),
            "dedup_merged": merged,
            "core_stations": sum(1 for s in deduped if s.region_id == "chongqing_core"),
            "jiangjin_stations": sum(1 for s in deduped if s.region_id == "jiangjin"),
            "core_routes": sum(1 for r in routes if r.region_id == "chongqing_core"),
            "jiangjin_routes": sum(1 for r in routes if r.region_id == "jiangjin"),
        }
        snap = TransitSnapshot(
            snapshot_version=snapshot_version or f"{region_id}-{time.strftime('%Y%m%d')}",
            created_at=time.time(),
            source=source,
            stations=list(deduped),
            routes=list(routes),
            route_stops=list(stops),
            counts=counts,
        )
        p = self.snap_dir / f"{snap.snapshot_version}.json"
        p.write_text(json.dumps({
            "snapshotVersion": snap.snapshot_version,
            "createdAt": snap.created_at,
            "source": source,
            "region": region_id,
            "graphVersion": graph_version,
            "counts": counts,
            "stations": [asdict(s) for s in snap.stations],
            "routes": [asdict(r) for r in snap.routes],
            "routeStops": snap.route_stops,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        (self.snap_dir / "manifest.json").write_text(json.dumps({
            "snapshotVersion": snap.snapshot_version,
            "graphVersion": graph_version,
            "counts": counts,
        }, indent=2), encoding="utf-8")
        return snap

    def load_latest(self) -> TransitSnapshot | None:
        files = sorted(self.snap_dir.glob("*.json"))
        files = [f for f in files if f.name != "manifest.json"]
        if not files:
            return None
        data = json.loads(files[-1].read_text(encoding="utf-8"))
        stations = [TransitStation(**s) for s in data.get("stations", [])]
        routes = [TransitRoute(**r) for r in data.get("routes", [])]
        return TransitSnapshot(
            snapshot_version=data.get("snapshotVersion", files[-1].stem),
            created_at=data.get("createdAt", 0.0),
            source=data.get("source", "osm"),
            stations=stations,
            routes=routes,
            route_stops=data.get("routeStops", []),
            counts=data.get("counts", {}),
        )

    def frozen(self, cfg: TransitRegionConfig) -> bool:
        return not cfg.refresh_transit_data
