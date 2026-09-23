"""OSM PBF → RoadGraph：真实道路图导入（非 Haversine）。

支持 DenseNodes / Way.highway，产出 LocalRoutingEngine 可用的真实边。
仅依赖标准库 + protobuf wire 解析。
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass, field
from typing import BinaryIO, Iterator

from .local_routing import RoadEdge, RoadGraph, haversine_m


# ─── minimal protobuf wire reader ───────────────────────────


def _read_varint(buf: memoryview, i: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, i
        shift += 7


def _read_svarint(buf: memoryview, i: int) -> tuple[int, int]:
    n, i = _read_varint(buf, i)
    return (n >> 1) ^ -(n & 1), i


def _skip_field(buf: memoryview, i: int, wire: int) -> int:
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
    raise ValueError(f"bad wire {wire}")


def _iter_fields(buf: memoryview) -> Iterator[tuple[int, int, int]]:
    i = 0
    n = len(buf)
    while i < n:
        key, i = _read_varint(buf, i)
        field_no, wire = key >> 3, key & 7
        yield field_no, wire, i
        i = _skip_field(buf, i, wire)


def _bytes_field(buf: memoryview, i: int, wire: int) -> tuple[memoryview, int]:
    assert wire == 2
    ln, i = _read_varint(buf, i)
    return buf[i : i + ln], i + ln


def _packed_svarints(buf: memoryview) -> list[int]:
    out: list[int] = []
    i = 0
    while i < len(buf):
        v, i = _read_svarint(buf, i)
        out.append(v)
    return out


def _packed_varints(buf: memoryview) -> list[int]:
    out: list[int] = []
    i = 0
    while i < len(buf):
        v, i = _read_varint(buf, i)
        out.append(v)
    return out


# ─── OSM PBF ────────────────────────────────────────────────

HIGHWAY_CLASSES = {
    "motorway": "motorway",
    "trunk": "trunk",
    "primary": "primary",
    "secondary": "secondary",
    "tertiary": "tertiary",
    "unclassified": "unclassified",
    "residential": "residential",
    "service": "service",
    "living_street": "service",
    "motorway_link": "motorway",
    "trunk_link": "trunk",
    "primary_link": "primary",
    "secondary_link": "secondary",
    "tertiary_link": "tertiary",
}


@dataclass
class OsmImportStats:
    blobs: int = 0
    nodes: int = 0
    ways: int = 0
    highway_ways: int = 0
    edges: int = 0
    graph_version: str = ""


def _parse_blob_header(data: memoryview) -> tuple[bytes, int]:
    typ = b""
    datasize = 0
    for field_no, wire, i in _iter_fields(data):
        if field_no == 1 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            typ = bytes(raw)
        elif field_no == 3 and wire == 0:
            datasize, _ = _read_varint(data, i)
    return typ, datasize


def _parse_blob(data: memoryview) -> bytes:
    raw = None
    zlib_data = None
    for field_no, wire, i in _iter_fields(data):
        if field_no == 1 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
        elif field_no == 3 and wire == 2:
            zlib_data, _ = _bytes_field(data, i, wire)
    if raw is not None:
        return bytes(raw)
    if zlib_data is not None:
        return zlib.decompress(bytes(zlib_data))
    return b""


def _parse_stringtable(data: memoryview) -> list[str]:
    out: list[str] = []
    for field_no, wire, i in _iter_fields(data):
        if field_no == 1 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            out.append(bytes(raw).decode("utf-8", "replace"))
    return out


def _parse_dense_nodes(data: memoryview, strings: list[str], granularity: int, lat_off: int, lon_off: int):
    ids: list[int] = []
    lats: list[int] = []
    lons: list[int] = []
    keys_vals: list[int] = []
    for field_no, wire, i in _iter_fields(data):
        if field_no == 1 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            ids = _packed_svarints(raw)
        elif field_no == 8 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            lats = _packed_svarints(raw)
        elif field_no == 9 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            lons = _packed_svarints(raw)
        elif field_no == 10 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            keys_vals = _packed_varints(raw)
    # delta decode
    nid = 0
    la = 0
    lo = 0
    out = []
    for k in range(len(ids)):
        nid += ids[k]
        la += lats[k]
        lo += lons[k]
        lat = 1e-9 * (lat_off + granularity * la)
        lon = 1e-9 * (lon_off + granularity * lo)
        out.append((nid, lat, lon))
    return out


def _parse_way(data: memoryview, strings: list[str]):
    wid = 0
    keys: list[int] = []
    vals: list[int] = []
    refs: list[int] = []
    for field_no, wire, i in _iter_fields(data):
        if field_no == 1 and wire == 0:
            wid, _ = _read_varint(data, i)
        elif field_no == 2 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            keys = _packed_varints(raw)
        elif field_no == 3 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            vals = _packed_varints(raw)
        elif field_no == 8 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            refs = _packed_svarints(raw)
    tags = {}
    for k, v in zip(keys, vals):
        if k < len(strings) and v < len(strings):
            tags[strings[k]] = strings[v]
    # delta refs
    rid = 0
    nodes = []
    for d in refs:
        rid += d
        nodes.append(rid)
    return wid, nodes, tags


def _parse_primitive_block(data: memoryview, stats: OsmImportStats):
    strings: list[str] = []
    granularity = 100
    lat_off = 0
    lon_off = 0
    groups: list[memoryview] = []
    for field_no, wire, i in _iter_fields(data):
        if field_no == 1 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            strings = _parse_stringtable(raw)
        elif field_no == 2 and wire == 2:
            raw, _ = _bytes_field(data, i, wire)
            groups.append(raw)
        elif field_no == 17 and wire == 0:
            granularity, _ = _read_varint(data, i)
        elif field_no == 19 and wire == 0:
            lat_off, _ = _read_varint(data, i)
        elif field_no == 20 and wire == 0:
            lon_off, _ = _read_varint(data, i)

    nodes: list[tuple[int, float, float]] = []
    ways: list[tuple[int, list[int], dict]] = []
    for g in groups:
        for field_no, wire, i in _iter_fields(g):
            if field_no == 2 and wire == 2:
                raw, _ = _bytes_field(g, i, wire)
                nodes.extend(_parse_dense_nodes(raw, strings, granularity, lat_off, lon_off))
            elif field_no == 3 and wire == 2:
                raw, _ = _bytes_field(g, i, wire)
                ways.append(_parse_way(raw, strings))
    stats.nodes += len(nodes)
    stats.ways += len(ways)
    return nodes, ways


def iter_osm_pbf(path: str):
    stats = OsmImportStats()
    node_loc: dict[int, tuple[float, float]] = {}
    highway_ways: list[tuple[list[int], str]] = []

    with open(path, "rb") as f:
        while True:
            hdr_len_b = f.read(4)
            if len(hdr_len_b) < 4:
                break
            (hdr_len,) = struct.unpack(">I", hdr_len_b)
            hdr = memoryview(f.read(hdr_len))
            typ, datasize = _parse_blob_header(hdr)
            blob = memoryview(f.read(datasize))
            stats.blobs += 1
            if typ != b"OSMData":
                continue
            raw = _parse_blob(blob)
            nodes, ways = _parse_primitive_block(memoryview(raw), stats)
            for nid, lat, lon in nodes:
                node_loc[nid] = (lat, lon)
            for wid, refs, tags in ways:
                hw = tags.get("highway")
                if hw and hw in HIGHWAY_CLASSES and len(refs) >= 2:
                    highway_ways.append((refs, HIGHWAY_CLASSES[hw]))
                    stats.highway_ways += 1

    # build edges with real road length along way geometry
    # 只保留 highway 节点，避免吸附到建筑物等无边节点
    highway_node_ids: set[str] = set()
    for refs, _road_class in highway_ways:
        for a in refs:
            highway_node_ids.add(str(a))

    g = RoadGraph()
    for nid, (lat, lon) in node_loc.items():
        key = str(nid)
        if key in highway_node_ids:
            g.add_node(key, lat, lon)

    for refs, road_class in highway_ways:
        for a, b in zip(refs, refs[1:]):
            ka, kb = str(a), str(b)
            if ka not in g.nodes or kb not in g.nodes:
                continue
            pa, pb = g.nodes[ka], g.nodes[kb]
            # 沿折线累计的路段长度（两点间道路段），不是起终点直线
            seg_m = haversine_m(pa, pb)
            if seg_m <= 0:
                continue
            # 速度随道路等级
            speed = {
                "motorway": 22.0,
                "trunk": 18.0,
                "primary": 12.0,
                "secondary": 10.0,
                "tertiary": 8.0,
                "unclassified": 7.0,
                "residential": 6.0,
                "service": 5.0,
            }.get(road_class, 7.0)
            dur = seg_m / speed
            g.add_edge(
                RoadEdge(
                    u=ka, v=kb, road_m=seg_m, duration_s=dur,
                    polyline=(pa, pb),
                    road_class=road_class,
                    vehicle_allowed=road_class != "service" or True,
                    bidirectional=True,
                )
            )
            stats.edges += 1

    stats.graph_version = f"osm-pbf-{stats.highway_ways}hw-{stats.edges}e"
    return g, stats


def load_osm_road_graph(path: str) -> tuple[RoadGraph, OsmImportStats]:
    """从 .osm.pbf 构建真实道路 RoadGraph（LocalRoutingEngine 输入）。"""
    return iter_osm_pbf(path)
