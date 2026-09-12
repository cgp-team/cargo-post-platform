"""从高德公交线路接口抓取真实公交线网，生成入库 SQL（站点 / 线路 / 线路站点序列）。

用法（仓库根目录）：
    python tools/gen_bus_network_sql.py --key <AMAP_WEB_KEY> --out sql/mysql/demo-real-bus-network.sql \
        --line "347路区间(老厂--上海城)" --line "320路(市五院新院区--渝南公交站场)" \
        --line "轨道交通3号线(鱼洞--江北机场T2航站楼)"

设计要点：
- 站点按"站名"去重（同名站共用一条 station 记录，坐标取首次出现的），id 从 --station-base 起递增；
- 线路 id 从 --route-base 起递增；线路站点序列来自高德 busstops 顺序，含坐标与站序；
- 输出 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行（幂等）。
"""

import argparse
import json
import time
import urllib.parse
import urllib.request

AMAP_BUS_LINE_URL = "https://restapi.amap.com/v3/bus/linename"
AMAP_AROUND_URL = "https://restapi.amap.com/v3/place/around"

"""非公交线路关键字：轨道交通/地铁/索道/轮渡/出租 一律排除（本项目只做公交）。"""
EXCLUDE_KEYWORDS = ("轨道", "地铁", "号线", "索道", "轮渡", "出租", "机场")


def is_bus_line(name: str) -> bool:
    return bool(name) and not any(k in name for k in EXCLUDE_KEYWORDS)


def fetch_around_bus_lines(key: str, location: str, radius: int) -> list[str]:
    """周边搜索公交站（POI 150700），汇总途经公交线路名（去重、排除非公交）。"""
    lines: list[str] = []
    page = 1
    while page <= 3:
        query = urllib.parse.urlencode({
            "key": key, "location": location, "radius": radius, "types": "150700",
            "offset": 25, "page": page, "extensions": "all",
        })
        with urllib.request.urlopen(f"{AMAP_AROUND_URL}?{query}", timeout=20) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
        if payload.get("status") != "1":
            raise RuntimeError(f"周边搜索失败: {payload.get('info')}")
        pois = payload.get("pois") or []
        if not pois:
            break
        for poi in pois:
            found = False
            for raw in (poi.get("buslines") or []):
                name = (raw.get("name") or "").strip()
                if is_bus_line(name) and name not in lines:
                    lines.append(name)
                    found = True
            # 兼容：部分 key/接口版本 buslines 为空数组，途经线路实际写在 address 里
            # （如 "0321路夜班车;115路;303路;347路区间;..."），这里做兜底解析
            if not found and poi.get("address"):
                for name in str(poi["address"]).replace("；", ";").split(";"):
                    name = name.strip()
                    if is_bus_line(name) and name not in lines:
                        lines.append(name)
        page += 1
        time.sleep(0.6)
    return lines


def fetch_line(key: str, keyword: str, city: str) -> dict:
    """按线路名查询，返回第一条线路（含 busstops 序列）。"""
    query = urllib.parse.urlencode({
        "key": key, "keywords": keyword, "city": city, "offset": 10, "extensions": "all",
    })
    with urllib.request.urlopen(f"{AMAP_BUS_LINE_URL}?{query}", timeout=20) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("status") != "1":
        raise RuntimeError(f"高德查询失败: {payload.get('info')}")
    lines = payload.get("buslines") or []
    if not lines:
        raise RuntimeError(f"未找到线路: {keyword}")
    for line in lines:
        if (line.get("name") or "") == keyword or (line.get("name") or "").startswith(keyword):
            return line
    return lines[0]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--key", required=True)
    parser.add_argument("--city", default="重庆")
    parser.add_argument("--out", required=True)
    parser.add_argument("--line", action="append", help="线路全名或线路号，例如 320 或 320路(市五院新院区--渝南公交站场)")
    parser.add_argument("--around", action="append",
                        help="周边公交站锚点 lon,lat（可多次），自动收集途经公交线路（仅公交，排除轨道/地铁）")
    parser.add_argument("--radius", type=int, default=3000, help="周边搜索半径(米)，默认 3000")
    parser.add_argument("--max-lines", type=int, default=15, help="最多抓取线路条数")
    parser.add_argument("--station-base", type=int, default=400)
    parser.add_argument("--route-base", type=int, default=401)
    parser.add_argument("--code-prefix", default="AMAP")
    args = parser.parse_args()

    station_ids: dict[str, int] = {}
    stations: list[tuple[int, str, float, float, str]] = []
    routes: list[tuple[int, str, str, int, int, float]] = []
    route_stations: list[tuple[int, int, int, int, float]] = []

    next_station = args.station_base
    route_id = args.route_base
    seq_row_id = args.route_base * 100

    keywords = list(args.line or [])
    for anchor in (args.around or []):
        time.sleep(1.2)
        found = fetch_around_bus_lines(args.key, anchor, args.radius)
        print(f"锚点 {anchor} 周边公交线路 {len(found)} 条：{'、'.join(found[:10])}{'…' if len(found) > 10 else ''}")
        for name in found:
            if name not in keywords:
                keywords.append(name)
    keywords = keywords[:args.max_lines]

    for keyword in keywords:
        time.sleep(1.5)  # 免费 Web 服务 key 有 QPS 限制，避免 CUQPS_HAS_EXCEEDED_THE_LIMIT
        try:
            line = fetch_line(args.key, keyword, args.city)
        except RuntimeError as exc:
            print(f"  跳过 {keyword}：{exc}")
            continue
        stops = line.get("busstops") or []
        route_name = line.get("name") or keyword
        start_id = end_id = None
        seq = 0
        for stop in stops:
            name = (stop.get("name") or "").strip()
            location = stop.get("location") or ""
            if not name or "," not in location:
                continue
            lon, lat = (float(x) for x in location.split(",")[:2])
            if name not in station_ids:
                station_ids[name] = next_station
                stations.append((next_station, name, lon, lat, (stop.get("cityname") or "")))
                next_station += 1
            station_id = station_ids[name]
            seq += 1
            route_stations.append((seq_row_id, route_id, station_id, seq, 0.0))
            seq_row_id += 1
            if start_id is None:
                start_id = station_id
            end_id = station_id
        routes.append((route_id, f"{args.code_prefix}{route_id}", route_name, start_id, end_id,
                       float(line.get("distance") or 0)))  # 高德 buslines[].distance 单位即公里
        route_id += 1

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("-- 由 tools/gen_bus_network_sql.py 从高德公交线路接口生成（真实站名/坐标/站序）\n")
        f.write("-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE\n\n")
        f.write("INSERT INTO transport_station\n"
                "    (id, station_code, station_name, station_level, longitude, latitude, address, status,\n"
                "     source_type, station_type, user_access, vehicle_access, dispatch_enabled,\n"
                "     tenant_id, creator, updater, deleted)\nVALUES\n")
        rows = [f"    ({sid}, 'AMAP{sid}', '{name}', 2, {lon:.7f}, {lat:.7f}, '{name}', 0, "
                f"'REAL', 'BUS_STOP', b'1', b'1', b'1', 0, '1', '1', b'0')"
                for sid, name, lon, lat, _ in stations]
        f.write(",\n".join(rows))
        f.write("\nON DUPLICATE KEY UPDATE\n    station_name = VALUES(station_name),\n"
                "    longitude = VALUES(longitude), latitude = VALUES(latitude),\n"
                "    source_type = VALUES(source_type), station_type = VALUES(station_type), deleted = b'0';\n\n")

        f.write("INSERT INTO transport_route\n"
                "    (id, route_code, route_name, start_station_id, end_station_id, distance_km, status,\n"
                "     source_type, service_type, dispatch_enabled, tenant_id, creator, updater, deleted)\nVALUES\n")
        rows = [f"    ({rid}, '{code}', '{name}', {start_id}, {end_id}, {km:.2f}, 0, "
                f"'REAL', 'MIXED', b'1', 0, '1', '1', b'0')"
                for rid, code, name, start_id, end_id, km in routes]
        f.write(",\n".join(rows))
        f.write("\nON DUPLICATE KEY UPDATE\n    route_name = VALUES(route_name),\n"
                "    start_station_id = VALUES(start_station_id), end_station_id = VALUES(end_station_id),\n"
                "    distance_km = VALUES(distance_km), source_type = VALUES(source_type), deleted = b'0';\n\n")

        route_ids = ",".join(str(r[0]) for r in routes)
        f.write(f"DELETE FROM transport_route_station WHERE route_id IN ({route_ids});\n\n")
        f.write("INSERT INTO transport_route_station\n"
                "    (id, route_id, station_id, sequence_no, planned_minutes, tenant_id, creator, updater, deleted)\nVALUES\n")
        rows = [f"    ({row_id}, {rid}, {sid}, {seq}, 0, 0, '1', '1', b'0')"
                for row_id, rid, sid, seq, _ in route_stations]
        f.write(",\n".join(rows))
        f.write("\nON DUPLICATE KEY UPDATE\n    sequence_no = VALUES(sequence_no), deleted = b'0';\n\n")
        f.write("SELECT '线路' AS info, id, route_name, distance_km FROM transport_route "
                f"WHERE id IN ({route_ids});\n")

    print(f"生成 {args.out}: 站点 {len(stations)} 个，线路 {len(routes)} 条，线路站点 {len(route_stations)} 行")
    for rid, code, name, _, _, km in routes:
        print(f"  - {name} ({km:.2f}km)")


if __name__ == "__main__":
    main()
