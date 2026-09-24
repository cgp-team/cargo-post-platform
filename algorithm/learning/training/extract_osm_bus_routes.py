# -*- coding: utf-8 -*-
"""按 OSM PBF 规范解析 Relation（Member 子消息 + delta id + role_sid）。"""
from __future__ import annotations

import json
import struct
import sys
import time
from pathlib import Path

ROOT = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\algorithm")
sys.path.insert(0, str(ROOT))

from learning.transit.osm_transit_discovery import (
    TransitRoute,
    TransitStation,
    classify_region,
    TransitRegionConfig,
    _parse_blob,
    _parse_blob_header,
    _parse_dense_nodes,
    _parse_stringtable,
    _parse_way,
    _parse_tags,
    _iter_fields,
    _bytes_field,
    _read_varint,
    _read_svarint,
    _packed_varints,
    _packed_svarints,
)

PBF = Path(r"C:\Users\袁\Desktop\测试\cargo-post-platform\tools\osm-data\chongqing-260921.osm.pbf")
OUT = ROOT / "data" / "transit" / "snapshots"


def parse_relation_fixed(data, strings):
    """OSM PBF Relation：8=roles(sid) 9=memids(delta packed) 10=types。"""
    rid, keys, vals = 0, [], []
    roles_sid, mem_deltas, mem_types = [], [], []
    try:
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
                roles_sid = _packed_svarints(raw)  # sint64 packed
            elif fn == 9 and w == 2:
                raw, _ = _bytes_field(data, i, w)
                mem_deltas = _packed_svarints(raw)  # sint64 packed, DELTA
            elif fn == 10 and w == 2:
                raw, _ = _bytes_field(data, i, w)
                mem_types = _packed_varints(raw)  # enum MemberType
    except Exception:
        return None
    tags = _parse_tags(keys, vals, strings)
    mem_roles = [strings[s] if 0 <= s < len(strings) else "" for s in roles_sid]
    mem_ids = []
    prev = 0
    for d in mem_deltas:
        prev += d
        mem_ids.append(prev)
    n = min(len(mem_ids), len(mem_types), len(mem_roles) if mem_roles else len(mem_ids))
    return rid, mem_ids[:n], mem_types[:n], (mem_roles[:n] if mem_roles else [""] * n), tags


def main():
    node_loc = {}
    node_tags = {}
    relations = []
    t0 = time.perf_counter()
    with PBF.open("rb") as f:
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
            strings = []
            granularity, lat_off, lon_off = 100, 0, 0
            groups = []
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
                    if fn == 2 and w == 2:
                        r, _ = _bytes_field(g, i, w)
                        for nid, lat, lon, tags in _parse_dense_nodes(r, strings, granularity, lat_off, lon_off):
                            node_loc[nid] = (lat, lon)
                            if tags:
                                node_tags[nid] = tags
                    elif fn == 4 and w == 2:
                        r, _ = _bytes_field(g, i, w)
                        rel = parse_relation_fixed(r, strings)
                        if rel:
                            relations.append(rel)
    print(f"pbf {time.perf_counter()-t0:.1f}s nodes={len(node_loc)} rels={len(relations)}", flush=True)

    bus_rels = [rel for rel in relations if (rel[4].get("route") in ("bus", "minibus", "share_taxi"))]
    print("route=bus relations:", len(bus_rels), flush=True)

    stations = {}
    for nid, tags in node_tags.items():
        if nid not in node_loc:
            continue
        is_stop = (
            tags.get("highway") == "bus_stop"
            or tags.get("public_transport") in ("platform", "stop_position", "station")
            or tags.get("amenity") == "bus_station"
        )
        if not is_stop:
            continue
        lat, lon = node_loc[nid]
        region = classify_region(lat, lon, TransitRegionConfig())
        if region is None:
            continue
        stations[str(nid)] = TransitStation(
            station_id=f"osm:{nid}", name=tags.get("name") or f"osm_node_{nid}",
            longitude=lon, latitude=lat, region_id=region, external_id=str(nid),
        )

    routes, route_stops = [], []
    for rid, mids, mtypes, mroles, tags in bus_rels:
        name = tags.get("name") or f"osm_route_{rid}"
        seq = []
        prev = 0
        for mid, mtype, role in zip(mids, mtypes, mroles):
            if mtype != 0:
                continue  # node only
            sid = f"osm:{mid}"
            tags_n = node_tags.get(mid) or {}
            is_named_stop = (
                sid in stations
                or role in ("stop", "platform", "stop_entry_only", "stop_exit_only")
                or tags_n.get("highway") == "bus_stop"
                or tags_n.get("public_transport") in ("platform", "stop_position")
                or (tags_n.get("name") and role in ("stop", "platform", ""))
            )
            if not is_named_stop:
                continue
            if sid not in stations and mid in node_loc:
                lat, lon = node_loc[mid]
                region = classify_region(lat, lon, TransitRegionConfig()) or "chongqing_core"
                stations[sid] = TransitStation(
                    station_id=sid, name=tags_n.get("name") or f"osm_node_{mid}",
                    longitude=lon, latitude=lat, region_id=region, external_id=str(mid),
                )
            if sid in stations and (not seq or seq[-1] != sid):
                seq.append(sid)
        if len(seq) < 3:
            continue
        direction = "BACKWARD" if "backward" in (tags.get("direction") or "").lower() else "FORWARD"
        first = stations.get(seq[0])
        region = first.region_id if first else "chongqing_core"
        rt = TransitRoute(
            route_id=f"osm_r:{rid}", name=name, region_id=region, direction=direction,
            start_station=seq[0], end_station=seq[-1], stop_ids=seq,
        )
        routes.append(rt)
        for i, sid in enumerate(seq):
            route_stops.append({"route_id": rt.route_id, "direction": direction, "sequence": i, "station_id": sid})

    core = [r for r in routes if len(r.stop_ids) >= 5]
    print(f"stations={len(stations)} routes={len(routes)} ge5={len(core)}", flush=True)
    if core:
        print("sample:", core[0].name, "stops", len(core[0].stop_ids), [stations[s].name for s in core[0].stop_ids[:6]], flush=True)

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "osm_routes_full.json").write_text(json.dumps({
        "stations": [s.__dict__ for s in stations.values()],
        "routes": [r.__dict__ for r in routes],
        "route_stops": route_stops,
        "counts": {"stations": len(stations), "routes": len(routes),
                   "route_stops": len(route_stops), "core_routes_ge5": len(core)},
    }, ensure_ascii=False), encoding="utf-8")
    print("saved", OUT / "osm_routes_full.json", flush=True)


if __name__ == "__main__":
    main()
