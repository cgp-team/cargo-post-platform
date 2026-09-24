"""OSMTransitDiscovery：从重庆 OSM PBF 提取真实公交站点/线路/方向。

输出 osm_stations.jsonl / osm_routes.jsonl / osm_route_stops.jsonl。
禁止虚构站点；仅保留 OSM 实际存在的数据。
"""

from __future__ import annotations

import json
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

from app.routing.local_routing import haversine_m
from .region import CORE_DISTRICTS, JIANGJIN_DISTRICTS, TransitRegionConfig


def _read_varint(buf: memoryview, i: int):
    result = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, i
        shift += 7


def _read_svarint(buf, i):
    n, i = _read_varint(buf, i)
    return (n >> 1) ^ -(n & 1), i


def _skip_field(buf, i, wire):
    if wire == 0:
        _, i = _read_varint(buf, i)
        return i
    if wire == 1:
        return i + 8
    if wire == 2:
        ln, i = _read_varint(buf, i)
        return i + ln
    if wire == 5:
        return i + 4
    raise ValueError(wire)


def _iter_fields(buf):
    i = 0
    n = len(buf)
    while i < n:
        key, i = _read_varint(buf, i)
        yield key >> 3, key & 7, i
        i = _skip_field(buf, i, key & 7)


def _bytes_field(buf, i, wire):
    ln, i = _read_varint(buf, i)
    return buf[i : i + ln], i + ln


def _packed_varints(buf):
    out, i = [], 0
    while i < len(buf):
        v, i = _read_varint(buf, i)
        out.append(v)
    return out


def _packed_svarints(buf):
    out, i = [], 0
    while i < len(buf):
        v, i = _read_svarint(buf, i)
        out.append(v)
    return out


def _parse_blob_header(data):
    typ, datasize = b"", 0
    for fn, w, i in _iter_fields(data):
        if fn == 1 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            typ = bytes(raw)
        elif fn == 3 and w == 0:
            datasize, _ = _read_varint(data, i)
    return typ, datasize


def _parse_blob(data):
    raw = zlib_data = None
    for fn, w, i in _iter_fields(data):
        if fn == 1 and w == 2:
            raw, _ = _bytes_field(data, i, w)
        elif fn == 3 and w == 2:
            zlib_data, _ = _bytes_field(data, i, w)
    if raw is not None:
        return bytes(raw)
    if zlib_data is not None:
        return zlib.decompress(bytes(zlib_data))
    return b""


def _parse_stringtable(data):
    out = []
    for fn, w, i in _iter_fields(data):
        if fn == 1 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            out.append(bytes(raw).decode("utf-8", "replace"))
    return out


def _parse_tags(keys, vals, strings):
    return {
        strings[k]: strings[v]
        for k, v in zip(keys, vals)
        if k < len(strings) and v < len(strings)
    }


def _parse_dense_nodes(data, strings, granularity, lat_off, lon_off):
    ids, lats, lons = [], [], []
    keys_vals = []
    for fn, w, i in _iter_fields(data):
        if fn == 1 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            ids = _packed_svarints(raw)
        elif fn == 8 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            lats = _packed_svarints(raw)
        elif fn == 9 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            lons = _packed_svarints(raw)
        elif fn == 10 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            keys_vals = _packed_varints(raw)
    # tags: alternating key/val indices, 0 = separator
    tag_map: dict[int, dict] = {}
    cur = {}
    ki = 0
    node_i = 0
    # simplified: build tags per node from keys_vals
    nid = la = lo = 0
    out = []
    kv_iter = iter(keys_vals)
    for k in range(len(ids)):
        nid += ids[k]
        la += lats[k]
        lo += lons[k]
        lat = 1e-9 * (lat_off + granularity * la)
        lon = 1e-9 * (lon_off + granularity * lo)
        tags = {}
        # consume until 0
        try:
            while True:
                kidx = next(kv_iter)
                if kidx == 0:
                    break
                vidx = next(kv_iter)
                if kidx < len(strings) and vidx < len(strings):
                    tags[strings[kidx]] = strings[vidx]
        except StopIteration:
            pass
        out.append((nid, lat, lon, tags))
    return out


def _parse_way(data, strings):
    wid, keys, vals, refs = 0, [], [], []
    for fn, w, i in _iter_fields(data):
        if fn == 1 and w == 0:
            wid, _ = _read_varint(data, i)
        elif fn == 2 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            keys = _packed_varints(raw)
        elif fn == 3 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            vals = _packed_varints(raw)
        elif fn == 8 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            refs = _packed_svarints(raw)
    tags = _parse_tags(keys, vals, strings)
    rid = 0
    nodes = []
    for d in refs:
        rid += d
        nodes.append(rid)
    return wid, nodes, tags


def _parse_relation(data, strings):
    """OSM PBF Relation：members 是 repeated Member 子消息（非 packed）。"""
    rid, keys, vals = 0, [], []
    roles_sid, mem_deltas, mem_types = [], [], []
    for fn, w, i in _iter_fields(data):
        if fn == 1 and w == 0:
            rid, _ = _read_varint(data, i)
        elif fn == 2 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            keys = _packed_varints(raw)
        elif fn == 3 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            vals = _packed_varints(raw)
        elif fn == 8 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            roles_sid = _packed_svarints(raw)
        elif fn == 9 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            mem_deltas = _packed_svarints(raw)
        elif fn == 10 and w == 2:
            raw, _ = _bytes_field(data, i, w)
            mem_types = _packed_varints(raw)
    tags = _parse_tags(keys, vals, strings)
    mem_roles = [strings[s] if 0 <= s < len(strings) else "" for s in roles_sid]
    mem_ids = []
    prev = 0
    for d in mem_deltas:
        prev += d
        mem_ids.append(prev)
    n = min(len(mem_ids), len(mem_types), len(mem_roles) if mem_roles else len(mem_ids))
    return rid, mem_ids[:n], mem_types[:n], (mem_roles[:n] if mem_roles else [""] * n), tags


@dataclass
class TransitStation:
    station_id: str
    name: str
    longitude: float
    latitude: float
    region_id: str
    source: str = "osm"
    external_id: str = ""
    route_ids: list[str] = field(default_factory=list)
    coordinate_system: str = "WGS84"


@dataclass
class TransitRoute:
    route_id: str
    name: str
    region_id: str
    direction: str = "FORWARD"
    start_station: str = ""
    end_station: str = ""
    stop_ids: list[str] = field(default_factory=list)
    source: str = "osm"


def classify_region(lat: float, lon: float, cfg: TransitRegionConfig | None = None) -> str | None:
    """粗 bbox：主城核心区 vs 江津；区外返回 None（EXCLUDED）。"""
    cfg = cfg or TransitRegionConfig()
    # 重庆主城大致 bbox
    if 29.35 <= lat <= 29.75 and 106.30 <= lon <= 106.75:
        return cfg.core_id
    # 江津大致 bbox
    if 28.90 <= lat <= 29.55 and 106.05 <= lon <= 106.65:
        return cfg.jiangjin_id
    return None


class OSMTransitDiscovery:
    """从 .osm.pbf 一次提取公交站点与 route=bus 关系（本地快照，不持续联网）。"""

    def __init__(self, pbf_path: str | Path, cfg: TransitRegionConfig | None = None):
        self.pbf = Path(pbf_path)
        self.cfg = cfg or TransitRegionConfig()

    def discover(self) -> dict:
        node_loc: dict[int, tuple[float, float]] = {}
        node_tags: dict[int, dict] = {}
        ways: dict[int, tuple[list[int], dict]] = {}
        relations: list[tuple] = []
        with self.pbf.open("rb") as f:
            while True:
                hdr_b = f.read(4)
                if len(hdr_b) < 4:
                    break
                (hdr_len,) = struct.unpack(">I", hdr_b)
                hdr = memoryview(f.read(hdr_len))
                typ, datasize = _parse_blob_header(hdr)
                blob = memoryview(f.read(datasize))
                if typ != b"OSMData":
                    continue
                raw = _parse_blob(blob)
                strings: list[str] = []
                granularity, lat_off, lon_off = 100, 0, 0
                groups: list[memoryview] = []
                for fn, w, i in _iter_fields(memoryview(raw)):
                    if fn == 1 and w == 2:
                        r, _ = _bytes_field(memoryview(raw), i, w)
                        strings = _parse_stringtable(r)
                    elif fn == 2 and w == 2:
                        r, _ = _bytes_field(memoryview(raw), i, w)
                        groups.append(r)
                    elif fn == 17 and w == 0:
                        granularity, _ = _read_varint(memoryview(raw), i)
                    elif fn == 19 and w == 0:
                        lat_off, _ = _read_varint(memoryview(raw), i)
                    elif fn == 20 and w == 0:
                        lon_off, _ = _read_varint(memoryview(raw), i)
                for g in groups:
                    for fn, w, i in _iter_fields(g):
                        if fn == 2 and w == 2:  # dense nodes
                            r, _ = _bytes_field(g, i, w)
                            for nid, lat, lon, tags in _parse_dense_nodes(r, strings, granularity, lat_off, lon_off):
                                node_loc[nid] = (lat, lon)
                                if tags:
                                    node_tags[nid] = tags
                        elif fn == 3 and w == 2:  # way
                            r, _ = _bytes_field(g, i, w)
                            wid, nodes, tags = _parse_way(r, strings)
                            ways[wid] = (nodes, tags)
                        elif fn == 4 and w == 2:  # relation
                            r, _ = _bytes_field(g, i, w)
                            relations.append(_parse_relation(r, strings))

        stations: dict[str, TransitStation] = {}
        # 1) bus_stop / platform / stop_position nodes
        for nid, tags in node_tags.items():
            is_stop = (
                tags.get("highway") == "bus_stop"
                or tags.get("public_transport") in ("platform", "stop_position", "station")
                or tags.get("amenity") == "bus_station"
            )
            if not is_stop or nid not in node_loc:
                continue
            lat, lon = node_loc[nid]
            region = classify_region(lat, lon, self.cfg)
            if region is None:
                continue
            name = tags.get("name") or f"osm_node_{nid}"
            stations[str(nid)] = TransitStation(
                station_id=f"osm:{nid}", name=name, longitude=lon, latitude=lat,
                region_id=region, external_id=str(nid),
            )

        # 2) route=bus relations → stops via way/node members
        routes: list[TransitRoute] = []
        route_stops: list[dict] = []
        for rid, mids, mtypes, mroles, tags in relations:
            rtype = tags.get("type")
            route_tag = tags.get("route")
            if rtype != "route" or route_tag not in ("bus", "minibus", "share_taxi"):
                continue
            name = tags.get("name") or f"osm_route_{rid}"
            # stop sequence: members with role stop/platform or node in stations
            seq = []
            for mid, mtype, role in zip(mids, mtypes, mroles):
                if role in ("stop", "platform", "stop_entry_only", "stop_exit_only") or (
                    mtype == 0 and str(mid) in {s.external_id for s in stations.values()}
                ):
                    sid = f"osm:{mid}"
                    if sid in stations or str(mid) in {s.external_id for s in stations.values()}:
                        seq.append(sid if sid in stations else f"osm:{mid}")
            if len(seq) < 2:
                continue
            # direction from tags
            direction = "FORWARD"
            if "backward" in (tags.get("direction") or "").lower():
                direction = "BACKWARD"
            # region by first stop
            first = stations.get(seq[0])
            region = first.region_id if first else self.cfg.core_id
            rt = TransitRoute(
                route_id=f"osm_r:{rid}", name=name, region_id=region,
                direction=direction, start_station=seq[0], end_station=seq[-1],
                stop_ids=seq,
            )
            routes.append(rt)
            for i, sid in enumerate(seq):
                route_stops.append({
                    "route_id": rt.route_id, "direction": direction,
                    "sequence": i, "station_id": sid,
                })
                if sid in stations and rt.route_id not in stations[sid].route_ids:
                    stations[sid].route_ids.append(rt.route_id)

        return {
            "stations": list(stations.values()),
            "routes": routes,
            "route_stops": route_stops,
            "counts": {
                "stations": len(stations),
                "routes": len(routes),
                "route_stops": len(route_stops),
                "core_stations": sum(1 for s in stations.values() if s.region_id == self.cfg.core_id),
                "jiangjin_stations": sum(1 for s in stations.values() if s.region_id == self.cfg.jiangjin_id),
                "core_routes": sum(1 for r in routes if r.region_id == self.cfg.core_id),
                "jiangjin_routes": sum(1 for r in routes if r.region_id == self.cfg.jiangjin_id),
            },
        }

    def write_snapshot(self, out_dir: str | Path) -> dict:
        out = Path(out_dir)
        out.mkdir(parents=True, exist_ok=True)
        result = self.discover()
        with (out / "osm_stations.jsonl").open("w", encoding="utf-8") as f:
            for s in result["stations"]:
                f.write(json.dumps(s.__dict__, ensure_ascii=False) + "\n")
        with (out / "osm_routes.jsonl").open("w", encoding="utf-8") as f:
            for r in result["routes"]:
                f.write(json.dumps(r.__dict__, ensure_ascii=False) + "\n")
        with (out / "osm_route_stops.jsonl").open("w", encoding="utf-8") as f:
            for rs in result["route_stops"]:
                f.write(json.dumps(rs, ensure_ascii=False) + "\n")
        manifest = {
            "source": "osm-pbf",
            "region_scope": ["chongqing_core", "jiangjin"],
            **result["counts"],
        }
        (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        return result["counts"]
