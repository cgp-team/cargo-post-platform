#!/usr/bin/env python
"""调度真实道路轨迹预热（高德配额恢复后跑一次）。

背景
----
调度可视化（按车辆 / 按订单）画的是运输段（``transport_leg``）的真实道路轨迹。
过去轨迹只在内存缓存 10 分钟：配额耗尽或服务重启就退化成"两点直线"。
现在后端取到真实轨迹会落库到 ``transport_leg.navigation_polyline``，
并提供预热接口 ``POST /admin-api/transport/dispatch/plan/prefetch-road``：

* 订单池（待入池 / 已入池）订单的「取 → 送」站点对；
* 今天方案里运输段的起终点对。

逐对调用高德并落库（服务内已做 350ms 串行节流，个人 key 不要并发）。

用法
----
    python tools/prefetch_dispatch_roads.py --token <后台登录 accessToken>
    python tools/prefetch_dispatch_roads.py --base-url http://127.0.0.1:48080 --token xxx

说明
----
* 需要后台登录后的 accessToken（与前端请求头 ``Authorization: Bearer <token>`` 一致）；
* 配额未恢复时接口返回 0 并如实报告，不写脏数据；
* 前端不再用两点直线兜底：没取到的段显示「真实路线获取中」，取到后自动重绘。
"""
from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request


def main() -> int:
    parser = argparse.ArgumentParser(description="调度真实道路轨迹预热")
    parser.add_argument("--base-url", default="http://127.0.0.1:48080", help="后端地址")
    parser.add_argument("--token", required=True, help="后台登录 accessToken")
    parser.add_argument("--tenant-id", default="1", help="租户编号（默认 1）")
    args = parser.parse_args()

    url = f"{args.base_url}/admin-api/transport/dispatch/plan/prefetch-road"
    request = urllib.request.Request(url, data=b"", method="POST")
    request.add_header("Authorization", f"Bearer {args.token}")
    request.add_header("tenant-id", str(args.tenant_id))
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=600) as resp:
            body = json.load(resp)
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        print(f"请求失败：{exc}")
        return 1
    if body.get("code") != 0:
        print(f"接口返回错误：{body}")
        return 1
    print(f"完成：本次取到并落库/预热 {body.get('data')} 段站点路线")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
