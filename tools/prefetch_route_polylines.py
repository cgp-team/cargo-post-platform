#!/usr/bin/env python
"""线路真实道路轨迹预取（配额恢复后跑一次即可全量补齐）。

背景
----
线路轨迹此前只有内存缓存（5~10 分钟），高德配额耗尽或服务重启就退回两点直线；
后端已支持把取到的真实道路几何落库到 ``transport_route.navigation_polyline``
（见 AppBusServiceImpl.loadStoredPolyline / persistPolyline）。

用法
----
    python tools/prefetch_route_polylines.py                 # 全部线路
    python tools/prefetch_route_polylines.py --route-id 103  # 只补自建线路 103
    python tools/prefetch_route_polylines.py --self-built    # 只补自建线路（PROJECT）

说明
----
* 走的是后端自己的 ``/app-api/transport/bus/line-polyline``（PermitAll），
  取到即由后端落库，无需本脚本写库；
* 高德个人 key QPS 很低，脚本默认每条线路间隔 1.2 秒，别并发；
* 配额未恢复时脚本会如实报告"未取到"（该线路仍是直线暂替），不会写脏数据。
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def fetch_json(url: str, timeout: int = 60):
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.load(resp)


def main() -> int:
    parser = argparse.ArgumentParser(description="线路真实道路轨迹预取")
    parser.add_argument("--base-url", default="http://127.0.0.1:48080", help="后端地址")
    parser.add_argument("--route-id", type=int, action="append", help="只补指定线路（可重复）")
    parser.add_argument("--self-built", action="store_true", help="只补自建线路（项目线路，接口按范围过滤）")
    parser.add_argument("--interval", type=float, default=1.2, help="每条线路之间的间隔秒数")
    args = parser.parse_args()

    # 线路清单：优先用 --route-id；否则用小程序同一套线路接口（带坐标的线路）
    route_ids = list(args.route_id or [])
    if not route_ids:
        lines = fetch_json(f"{args.base_url}/app-api/transport/bus/lines?radius=50000").get("data") or []
        for line in lines:
            rid = line.get("id") or line.get("routeId")
            if rid is None:
                continue
            if args.self_built and (line.get("dataSource") or "").upper() != "PROJECT_TRANSIT":
                continue
            route_ids.append(rid)

    if not route_ids:
        print("没有需要预取的线路（可显式传 --route-id）")
        return 0

    ok, failed = [], []
    for rid in route_ids:
        url = f"{args.base_url}/app-api/transport/bus/line-polyline?routeId={urllib.parse.quote(str(rid))}"
        try:
            data = fetch_json(url)
            points = data.get("data") or []
            if len(points) >= 2:
                ok.append(rid)
                print(f"线路 {rid}: 取到 {len(points)} 个轨迹点（已由后端落库）")
            else:
                failed.append(rid)
                print(f"线路 {rid}: 未取到真实道路（仍为直线暂替；配额恢复后重跑）")
        except (urllib.error.URLError, TimeoutError, ValueError) as exc:
            failed.append(rid)
            print(f"线路 {rid}: 请求失败 {exc}")
        time.sleep(max(0.0, args.interval))

    print(f"\n完成：成功 {len(ok)} 条，未取到 {len(failed)} 条")
    if failed:
        print("未取到的线路（配额恢复后重跑）：", ", ".join(str(x) for x in failed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
