#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从重庆 OSM PBF 抽取区县级行政边界，组装多边形，顶点转 GCJ-02，Douglas-Peucker
简化后输出前端/后端共用的边界 JSON，并内置验证。

用法:
  python extract_districts.py                      # 默认: 脚本同目录 chongqing-*.osm.pbf
                                                   #       输出同目录 chongqing-districts-gcj02.json
  python extract_districts.py --pbf <path> --out <path>
  python extract_districts.py --verify <json>      # 仅验证已有 JSON（区县名单/包围盒/点包含/大小）

依赖 (venv):  pip install osmium shapely      # PyPI 包名 osmium 即 pyosmium
注意: 本机 osmium 的 C++ Reader 对非 ASCII 路径有问题，脚本会先 chdir 到 PBF 所在目录，
      再用相对文件名读取。
"""
import argparse
import json
import math
import os
import sys
import time
from pathlib import Path

import osmium
from shapely.geometry import Polygon, LinearRing, Point
from shapely.ops import unary_union
from shapely.validation import make_valid

# ---------------------------------------------------------------- 常量

EXPECTED_38 = [
    "渝中", "江北", "南岸", "沙坪坝", "九龙坡", "大渡口", "北碚", "渝北", "巴南",
    "长寿", "江津", "合川", "永川", "南川", "綦江", "大足", "璧山", "铜梁",
    "潼南", "荣昌", "开州", "梁平", "武隆", "城口", "丰都", "垫江", "忠县",
    "云阳", "奉节", "巫山", "巫溪", "石柱", "秀山", "酉阳", "彭水",
    "万州", "涪陵", "黔江",
]
# 验证点 (GCJ-02 坐标, 与输出同坐标系)
TEST_POINTS = [
    ("重庆邮电大学", 106.581, 29.537, "南岸"),
    ("朝天门", 106.592, 29.570, "渝中"),
]
MAX_BYTES = 400 * 1024
TARGET_LEVELS = ("5", "6", "7")   # 6=区县; 5/7 兜底检查
# 特殊区划(非标准行政区但按"有多少收多少"): 名称包含这些关键词且位于重庆境内
SPECIAL_KEYS = ("高新", "万盛", "新区", "经开区", "开发区")


def log(msg=""):
    print(msg, flush=True)


# ---------------------------------------------------------------- GCJ-02

def wgs84_to_gcj02(lng, lat):
    """中国公开地图标准火星坐标转换（纯 Python，无需外部服务）。不转 BD-09。"""
    a = 6378245.0
    ee = 0.00669342162296594323

    def _transform_lat(x, y):
        ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
        ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320.0 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
        return ret

    def _transform_lng(x, y):
        ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
        ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 50.0 * math.pi)) * 2.0 / 3.0
        return ret

    dlat = _transform_lat(lng - 105.0, lat - 35.0)
    dlng = _transform_lng(lng - 105.0, lat - 35.0)
    radlat = lat / 180.0 * math.pi
    magic = math.sin(radlat)
    magic = 1 - ee * magic * magic
    sqrtmagic = math.sqrt(magic)
    dlat = dlat * 180.0 / ((a * (1 - ee)) / (magic * sqrtmagic) * math.pi)
    dlng = dlng * 180.0 / (a / sqrtmagic * math.cos(radlat) * math.pi)
    return lng + dlng, lat + dlat


# ---------------------------------------------------------------- PBF 读取

class RelHandler(osmium.SimpleHandler):
    """第 1 遍: 收集目标 relation。
    - boundary=administrative 且 admin_level∈{4,5,6,7} (4 级仅收 重庆市 参照面)
    - boundary=historic 且 old_admin_level=6 (本快照中 江北区/渝北区 被降级为 historic)
    注: pyosmium RelationMember.type 是字符串 'n'/'w'/'r'。"""

    def __init__(self):
        super().__init__()
        self.relations = []          # [{id, level, kind, name, members:[(way_id, role)], rel_members}]
        self.member_of = set()       # 所有目标 relation 的成员 way id

    def relation(self, r):
        tags = {t.k: t.v for t in r.tags}
        name = tags.get("name") or tags.get("name:zh") or ""
        b = tags.get("boundary")
        lvl = tags.get("admin_level")
        if b == "administrative" and lvl == "4":
            if "重庆" not in name:
                return                      # 其他省参照面不需要
            kind, level = "reference", "4"
        elif b == "administrative" and lvl in TARGET_LEVELS:
            kind, level = "admin", lvl
        elif b == "historic" and tags.get("old_admin_level") == "6":
            kind, level = "historic", "6"
        else:
            return
        members, rel_members = [], 0
        for m in r.members:
            if m.type == "w":
                members.append((m.ref, m.role or "outer"))
                self.member_of.add(m.ref)
            elif m.type == "r":
                rel_members += 1
        self.relations.append(dict(
            id=r.id, level=level, kind=kind, name=name,
            members=members, rel_members=rel_members))


class WayHandler(osmium.SimpleHandler):
    """第 2 遍: 带 locations 解析成员 way 几何 + 收录独立闭合的 admin way。"""

    def __init__(self, member_ids):
        super().__init__()
        self.member_ids = member_ids
        self.ways = {}               # way_id -> [(lat,lon), ...]
        self.standalone = []         # 闭合且非成员的 admin way 元数据+几何
        self.admin_way_inv = []      # 所有 5/6/7 级 admin way 清单(报告用)
        self.bad_coords = 0

    def way(self, w):
        tags = {t.k: t.v for t in w.tags}
        is_admin = tags.get("boundary") == "administrative"
        level = tags.get("admin_level")
        wid = w.id
        if wid in self.member_ids or (is_admin and level in TARGET_LEVELS):
            geom = []
            for n in w.nodes:
                lat, lon = n.lat, n.lon
                if not (20.0 < lat < 40.0 and 95.0 < lon < 125.0):
                    self.bad_coords += 1
                    continue
                geom.append((lat, lon))
            if wid in self.member_ids:
                self.ways[wid] = geom
            if is_admin and level in TARGET_LEVELS:
                closed = len(geom) >= 4 and geom[0] == geom[-1]
                name = tags.get("name") or tags.get("name:zh") or ""
                self.admin_way_inv.append((level, name, wid, closed))
                if closed and wid not in self.member_ids:
                    self.standalone.append(dict(
                        id=wid, level=level, name=name, geom=geom))


# ---------------------------------------------------------------- 组装

def _clean_line(pts):
    out = [pts[0]]
    for p in pts[1:]:
        if p != out[-1]:
            out.append(p)
    return out


def chain_rings(way_geoms):
    """把若干 way 线段按端点串成闭合环。返回 (rings, 未能闭合的线数)。"""
    lines = []
    for g in way_geoms:
        if len(g) < 2:
            continue
        c = _clean_line(g)
        if len(c) >= 2:
            lines.append(c)
    used = [False] * len(lines)
    rings, orphans = [], 0
    for i in range(len(lines)):
        if used[i]:
            continue
        used[i] = True
        ring = lines[i]
        progress = True
        while progress and ring[0] != ring[-1]:
            progress = False
            for j in range(len(lines)):
                if used[j]:
                    continue
                c = lines[j]
                if c[-1] == ring[0]:
                    ring = c[:-1] + ring
                elif c[0] == ring[0]:
                    ring = c[::-1][:-1] + ring
                elif ring[-1] == c[0]:
                    ring = ring + c[1:]
                elif ring[-1] == c[-1]:
                    ring = ring + c[::-1][1:]
                else:
                    continue
                used[j] = True
                progress = True
                break
        if ring[0] != ring[-1]:
            # 端点在浮点上近似重合时强制闭合
            if abs(ring[0][0] - ring[-1][0]) < 1e-7 and abs(ring[0][1] - ring[-1][1]) < 1e-7:
                ring[-1] = ring[0]
        if ring[0] == ring[-1] and len(ring) >= 4:
            rings.append(ring)
        else:
            orphans += 1
    return rings, orphans


def rings_to_polygons(outer_rings, inner_rings, stats):
    """外环成面、内环按包含关系挂洞；buffer(0)/make_valid 处理自交。返回 Polygon 列表。"""
    shells = []
    for r in outer_rings:
        try:
            shells.append(Polygon(r))
        except Exception:
            stats["bad_rings"] += 1
    holes = [[] for _ in shells]
    for r in inner_rings:
        try:
            pt = Point(r[0])
        except Exception:
            continue
        cand = [k for k, s in enumerate(shells) if s.is_valid and s.covers(pt)]
        if not cand:
            cand = [k for k, s in enumerate(shells) if s.covers(pt)]
        if cand:
            k = min(cand, key=lambda i: shells[i].area)
            holes[k].append(r)
        else:
            stats["orphan_inners"] += 1
    polys = []
    for k, shell in enumerate(shells):
        try:
            p = Polygon(shell.exterior.coords, holes[k])
        except Exception:
            stats["bad_rings"] += 1
            continue
        if not p.is_valid:
            p = make_valid(p)
        for g in getattr(p, "geoms", [p]):
            if g.geom_type == "Polygon" and not g.is_empty and g.area > 1e-8:
                polys.append(g)
    return polys


def assemble_relation(rel, ways, stats):
    outer_geoms, inner_geoms = [], []
    seen = set()
    for wid, role in rel["members"]:
        if wid in seen:
            continue
        seen.add(wid)
        g = ways.get(wid)
        if not g or len(g) < 2:
            stats["missing_ways"] += 1
            continue
        (inner_geoms if role == "inner" else outer_geoms).append(g)
    if not outer_geoms:
        stats["failed"].append(f"{rel['name'] or rel['id']}(无外环)")
        return []
    out_rings, o1 = chain_rings(outer_geoms)
    in_rings, o2 = chain_rings(inner_geoms) if inner_geoms else ([], 0)
    stats["open_lines"] += o1 + o2
    if not out_rings:
        stats["failed"].append(f"{rel['name'] or rel['id']}(外环未闭合)")
        return []
    return rings_to_polygons(out_rings, in_rings, stats)


# ---------------------------------------------------------------- 输出与简化

def quantize_ring(coords, ndigits):
    ring, n = [], 0
    for x, y in coords:
        q = (round(x, ndigits), round(y, ndigits))
        if not ring or q != ring[-1]:
            ring.append(q)
            n += 1
    if ring and ring[0] != ring[-1]:
        ring.append(ring[0])
    if len(ring) < 4:
        return None
    return [[x, y] for x, y in ring]


def emit_json(districts, tol, ndigits):
    """districts: [(name, [Polygon(gcj02)...])] -> (data dict, json str, 点数)"""
    entries = []
    npoints = 0
    for name, polys in districts:
        for poly in polys:
            s = poly.simplify(tol, preserve_topology=True)
            for g in getattr(s, "geoms", [s]):
                if g.geom_type != "Polygon" or g.is_empty:
                    continue
                rings = []
                for ring in [g.exterior] + list(g.interiors):
                    q = quantize_ring(list(ring.coords), ndigits)
                    if q:
                        rings.append(q)
                        npoints += len(q)
                if rings:
                    entries.append({"name": name, "rings": rings})
    entries.sort(key=lambda d: d["name"])
    data = {"source": None, "coordinateSystem": "GCJ-02", "districts": entries}
    blob = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return data, blob, npoints


# ---------------------------------------------------------------- 验证

def poly_from_entry(e):
    try:
        p = Polygon(e["rings"][0], e["rings"][1:])
        if not p.is_valid:
            p = make_valid(p)
        return p
    except Exception:
        return None


def normalize(name):
    n = (name or "").strip()
    for pre in ("重庆市", "重庆"):
        if n.startswith(pre):
            n = n[len(pre):]
    return n


def validate(json_path, extra_note=""):
    raw = Path(json_path).read_bytes()
    data = json.loads(raw.decode("utf-8"))
    entries = data["districts"]
    names = [e["name"] for e in entries]
    uniq = sorted(set(names))
    norm = {normalize(n) for n in uniq}

    missing = [t for t in EXPECTED_38 if not any(t in n for n in norm)]
    extra = [n for n in sorted(norm) if not any(t in n for t in EXPECTED_38)]

    lons = [p[0] for e in entries for r in e["rings"] for p in r]
    lats = [p[1] for e in entries for r in e["rings"] for p in r]
    bbox = (min(lons), min(lats), max(lons), max(lats)) if lons else None

    # 面积估算 (deg² -> km², GCJ 近似 WGS 用于面积量级校验)
    def area_km2(e):
        a = 0.0
        for i, ring in enumerate(e["rings"]):
            s = 0.0
            n = len(ring)
            for k in range(n - 1):
                x1, y1 = ring[k]
                x2, y2 = ring[k + 1]
                s += x1 * y2 - x2 * y1
            a += abs(s) / 2.0 if i == 0 else -abs(s) / 2.0
        midlat = sum(p[1] for r in e["rings"] for p in r) / max(1, sum(len(r) for r in e["rings"]))
        return a * (111.32 * math.cos(math.radians(midlat))) * 110.57

    per_name = {}
    for e in entries:
        per_name.setdefault(e["name"], []).append(area_km2(e))
    exp_area = sum(sum(v) for k, v in per_name.items()
                   if any(t in normalize(k) for t in EXPECTED_38))
    spec_area = sum(sum(v) for k, v in per_name.items()
                    if not any(t in normalize(k) for t in EXPECTED_38))
    multi = {k: len(v) for k, v in per_name.items() if len(v) > 1}

    results = []
    for pname, x, y, token in TEST_POINTS:
        pt = Point(x, y)
        hit = []
        for e in entries:
            if token in normalize(e["name"]):
                poly = poly_from_entry(e)
                if poly is not None and poly.covers(pt):
                    hit.append(e["name"])
                    break
        results.append((pname, token, bool(hit)))

    ok = True
    log("")
    log("=" * 62)
    log(f"验证报告  {json_path} {extra_note}")
    log("=" * 62)
    log(f"文件大小      : {len(raw)} bytes (≤ {MAX_BYTES} {'OK' if len(raw) <= MAX_BYTES else 'FAIL'})")
    ok &= len(raw) <= MAX_BYTES
    log(f"条目数(parts) : {len(entries)}   唯一区县名: {len(uniq)}")
    log(f"区县名单({len(uniq)}): {'、'.join(uniq)}")
    log(f"对照38区县缺失: {'、'.join(missing) if missing else '(无)'}")
    log(f"名单外多余    : {'、'.join(extra) if extra else '(无)'}")
    if bbox:
        log(f"包围盒        : lon [{bbox[0]:.4f}, {bbox[2]:.4f}]  lat [{bbox[1]:.4f}, {bbox[3]:.4f}]"
            f"  {'OK' if 105 <= bbox[0] and bbox[2] <= 110.5 and 28 <= bbox[1] and bbox[3] <= 33 else 'WARN'}")
    log(f"面积(估)      : 38区县合计 ≈{exp_area:.0f} km² (重庆约82400); 特殊区划 ≈{spec_area:.0f} km²")
    log(f"多块区县      : {'; '.join(f'{k}x{v}' for k, v in sorted(multi.items())) or '(无)'}")
    for pname, token, okp in results:
        log(f"点包含测试    : {pname} ({token}) -> {'PASS' if okp else 'FAIL'}")
        ok &= okp
    if extra_note:
        log(extra_note)
    log("=" * 62)
    return ok


# ---------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser(description="抽取重庆区县边界 -> GCJ-02 JSON")
    here = Path(__file__).resolve().parent
    ap.add_argument("--pbf", default=str(here / "chongqing-260921.osm.pbf"))
    ap.add_argument("--out", default=str(here / "chongqing-districts-gcj02.json"))
    ap.add_argument("--verify", default=None, help="仅验证已有 JSON")
    args = ap.parse_args()

    if args.verify:
        sys.exit(0 if validate(args.verify) else 1)

    t0 = time.time()
    pbf = Path(args.pbf).resolve()
    out = Path(args.out).resolve()
    if not pbf.is_file():
        log(f"PBF 不存在: {pbf}")
        sys.exit(1)
    # osmium C++ Reader 在 Windows 对非 ASCII 路径会报 "file not found": chdir + 相对路径
    os.chdir(pbf.parent)
    pbf_rel = pbf.name

    # ---- 第 1 遍: relations
    log(f"[1/5] 扫描 relations ... ({pbf.name})")
    relh = RelHandler()
    relh.apply_file(pbf_rel, locations=False, idx="flex_mem")
    ref_rels = [r for r in relh.relations if r["kind"] == "reference"]
    lv6 = [r for r in relh.relations if r["kind"] == "admin" and r["level"] == "6"]
    lv57 = [r for r in relh.relations if r["kind"] == "admin" and r["level"] in ("5", "7")]
    hist = [r for r in relh.relations if r["kind"] == "historic"]
    log(f"      重庆市参照L4: {len(ref_rels)}; admin L6: {len(lv6)}; "
        f"L5/7: {len(lv57)}; historic-old6: {len(hist)}")
    for r in sorted(lv57 + hist, key=lambda x: (x["kind"], x["level"], x["name"])):
        log(f"      [候选兜底] {r['kind']} L{r['level']} {r['name'] or '(无名)'} "
            f"(rel {r['id']}, way成员 {len(r['members'])})")

    # ---- 第 2 遍: ways
    log("[2/5] 扫描 ways (locations 解析坐标) ...")
    wayh = WayHandler(relh.member_of)
    wayh.apply_file(pbf_rel, locations=True, idx="flex_mem")
    log(f"      成员 way 几何 {len(wayh.ways)} 条; 独立闭合 admin way {len(wayh.standalone)} 条;"
        f" 越界坐标 {wayh.bad_coords}")
    for w in sorted(wayh.standalone, key=lambda x: (x["level"], x["name"])):
        p0 = w["geom"][0]
        log(f"      [独立way] L{w['level']} {w['name'] or '(无名)'} (way {w['id']}) "
            f"起点 lat={p0[0]:.5f},lon={p0[1]:.5f}, 顶点{len(w['geom'])}")

    # ---- 组装
    log("[3/5] 组装多边形 (outer/inner 环) ...")
    stats = dict(missing_ways=0, open_lines=0, bad_rings=0, orphan_inners=0, failed=[])
    # name -> [Polygon]
    buckets = {}
    order = []
    display_name = {}   # 归一化 key -> 首次见到的原始 name (输出用)

    def add(name, polys):
        key = normalize(name)
        if not key:
            key = "(无名)"
        if key not in buckets:
            buckets[key] = []
            order.append(key)
            display_name[key] = (name or "").strip() or key
        buckets[key].extend(polys)

    # 重庆市参照面: 用于把邻省区县等非重庆实体过滤掉
    ref_poly = None
    for rel in ref_rels:
        polys = assemble_relation(rel, wayh.ways, stats)
        if polys:
            ref_poly = unary_union(polys)
            log(f"      重庆市参照面: {len(polys)} 块, area≈{ref_poly.area:.2f} deg²")
    if ref_poly is None or ref_poly.area < 5.0:
        log("      WARN: 重庆市参照面不可用, 地理过滤退化为仅按名称")
        ref_poly = None

    def inside_cq(poly):
        if ref_poly is None:
            return True
        if ref_poly.covers(poly.representative_point()):
            return True
        try:
            return poly.area > 0 and ref_poly.intersection(poly).area / poly.area > 0.6
        except Exception:
            return False

    excluded = []
    for rel in lv6:
        polys = assemble_relation(rel, wayh.ways, stats)
        kept = [p for p in polys if inside_cq(p)]
        note = "" if rel["rel_members"] == 0 else f" (嵌套relation成员x{rel['rel_members']} 未展开)"
        if polys and not kept:
            excluded.append(rel["name"] or f"rel{rel['id']}")
            log(f"      [排除-非重庆] L6 {rel['name'] or rel['id']}: {len(polys)} 块{note}")
            continue
        if kept:
            add(rel["name"], kept)
        log(f"      L6 {rel['name'] or rel['id']}: {len(kept)}/{len(polys)} 块{note}")

    # ---- 兜底: 5/7 级与 historic-old6 —— 名字命中"当前缺失的38区县"或特殊区划关键词, 且在重庆境内
    found_now = set(buckets)
    missing_now = [t for t in EXPECTED_38 if not any(t in n for n in found_now)]
    if missing_now:
        log(f"      当前缺失: {'、'.join(missing_now)} -> 兜底检查 5/7 级 + historic 关系")
    for rel in lv57 + hist:
        n = normalize(rel["name"])
        if not n:
            continue
        hit = any(t in n for t in missing_now) or any(k in n for k in SPECIAL_KEYS)
        if not hit:
            continue
        polys = assemble_relation(rel, wayh.ways, stats)
        kept = [p for p in polys if inside_cq(p)]
        if kept:
            add(rel["name"], kept)
            log(f"      [兜底纳入] {rel['kind']} L{rel['level']} {rel['name']} -> {len(kept)} 块")
        else:
            log(f"      [兜底失败] {rel['kind']} L{rel['level']} {rel['name']}: {len(polys)} 块/境内 {len(kept)}")
    for w in wayh.standalone:
        n = normalize(w["name"])
        if w["level"] == "6" and n and any(t in n for t in missing_now):
            r, _ = chain_rings([w["geom"]])
            polys = rings_to_polygons(r, [], stats)
            kept = [p for p in polys if inside_cq(p)]
            if kept:
                add(w["name"], kept)
                log(f"      [兜底纳入] L6 独立way {w['name']} -> {len(kept)} 块")

    # ---- 独立闭合 way (L6): 与已有同名面重复的丢弃, 否则作为附加 parts
    for w in wayh.standalone:
        if w["level"] != "6":
            continue
        n = normalize(w["name"])
        if not n:
            continue
        r, _ = chain_rings([w["geom"]])
        polys = rings_to_polygons(r, [], stats)
        kept = [p for p in polys if inside_cq(p)]
        if not kept:
            continue
        rep = kept[0].representative_point()
        if any(p.covers(rep) for p in buckets.get(n, [])):
            log(f"      [独立way] {w['name']} 与已有面重复, 跳过")
            continue
        add(w["name"], kept)
        log(f"      [独立way 纳入] L6 {w['name']} -> {len(kept)} 块")

    if excluded:
        log(f"      已排除(地理过滤): {'、'.join(excluded)}")
    if stats["failed"]:
        log(f"      组装失败: {stats['failed']}")
    log(f"      stats: 缺成员way {stats['missing_ways']}, 未闭合线 {stats['open_lines']}, "
        f"坏环 {stats['bad_rings']}, 孤立内环 {stats['orphan_inners']}")

    # ---- WGS-84 -> GCJ-02 (仅顶点整体转换, 不改拓扑)
    # 注意: 组装阶段的面片坐标是 shapely (x, y) = (lat, lon); 转换后统一为 (lng, lat)
    log("[4/5] 顶点 WGS-84 -> GCJ-02 ...")
    districts = []
    total_pts = 0
    for key in order:
        polys = buckets[key]
        gcj = []
        for p in polys:
            shell = []
            for x, y in p.exterior.coords:          # x=lat, y=lon
                lng, lat = wgs84_to_gcj02(y, x)
                shell.append((lng, lat))
            ints = []
            for i in p.interiors:
                ring = []
                for x, y in i.coords:
                    lng, lat = wgs84_to_gcj02(y, x)
                    ring.append((lng, lat))
                ints.append(ring)
            gp = Polygon(shell, ints)
            if not gp.is_valid:
                gp = make_valid(gp)
            for g in getattr(gp, "geoms", [gp]):
                if g.geom_type == "Polygon" and not g.is_empty:
                    gcj.append(g)
                    total_pts += len(g.exterior.coords)
        if gcj:
            districts.append((display_name.get(key, key), gcj))
    log(f"      {len(districts)} 个名称, {sum(len(p) for _, p in districts)} 块面, "
        f"原始顶点 ~{total_pts}")

    # ---- 简化 + 落盘 (容差从 0.0003 起自适应, 目标 ≤400KB)
    log("[5/5] Douglas-Peucker 简化 + 写出 ...")
    source = f"OSM {pbf.name}"
    best = None
    tol, ndigits = 0.0003, 5
    while ndigits >= 4:
        while tol <= 0.06:
            data, blob, npoints = emit_json(districts, tol, ndigits)
            data["source"] = source
            blob = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
            size = len(blob.encode("utf-8"))
            if size <= MAX_BYTES:
                best = (data, blob, tol, ndigits, npoints, size)
                break
            tol = round(tol * 1.4, 8)
        if best:
            break
        ndigits = 4
        tol = 0.0003
    if not best:
        log("未能在容差范围内压到 400KB 以下")
        sys.exit(1)
    data, blob, tol, ndigits, npoints, size = best
    out.write_text(blob, encoding="utf-8")
    log(f"      容差 {tol}°, 坐标 {ndigits} 位小数, 输出 {npoints} 点, {size} bytes")

    ok = validate(str(out), extra_note=f"耗时 {time.time() - t0:.1f}s")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
