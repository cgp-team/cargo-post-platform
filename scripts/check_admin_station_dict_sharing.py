#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""站点全量字典的共享与失效静态闸（队列 #75 / 台账 D-128）。

两条都是"不会报错、只会悄悄变坏"的形态：
· 谁都能直接 `StationApi.getSimpleStationList()`：后端这个口不分页返回**全量站点**，
  以前 6 个页面/弹窗各拉一遍，开一次弹窗就重下一遍整张表。收成一层缓存之后，
  必须防止下一个 PR 又把直连调用加回来 —— 那才是这轮改动的真实风险。
· 站点被增删改之后不失效缓存，就变成"刚建的站点在别的页面选不到"，
  那是把一个性能问题换成一个正确性问题，比原来更糟。

口径：
· `getSimpleStationList` 只允许出现在两处：API 定义文件本体，和 `store/modules/stationDict.ts`。
· 每个调用了 `createStation/updateStation/deleteStation` 的文件，必须同时出现
  `stationDict.invalidate()`，且次数不少于该文件里的站点写调用数（少一次就是漏了一格）。

用法：python3 scripts/check_admin_station_dict_sharing.py [src 根]
退出码：0 = 达标；1 = 有违规；2 = 扫描根不存在 / 一个站点写口都没数到（判据没跑起来）。
"""
import io
import os
import re
import sys

DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src")
ALLOWED = {
    "api/transport/station/index.ts",   # 定义本身
    "store/modules/stationDict.ts",     # 唯一允许直连的地方
}
API_CALL = "getSimpleStationList"
WRITE_CALLS = ("createStation", "updateStation", "deleteStation")


def main():
    root = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("--") else DEFAULT_ROOT
    if not os.path.isdir(root):
        sys.stderr.write("扫描根不存在：%s\n" % root)
        return 2
    direct, writes, invalidations = [], 0, 0
    per_file_writes = {}
    scanned = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d not in ("node_modules", "__tests__")]
        for fn in sorted(filenames):
            if not fn.endswith((".vue", ".ts")):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            text = io.open(path, encoding="utf-8", errors="replace").read()
            scanned += 1
            if API_CALL in text and rel not in ALLOWED:
                for i, line in enumerate(text.split("\n"), 1):
                    if API_CALL in line and not line.strip().startswith("//"):
                        direct.append((rel, i, line.strip()[:90]))
            w = 0
            for name in WRITE_CALLS:
                w += len(re.findall(r'StationApi\.%s\(' % name, text))
            if w:
                writes += w
                per_file_writes[rel] = per_file_writes.get(rel, 0) + w
                invalidations += text.count("stationDict.invalidate()")
    print("[扫描] %s ⇒ %d 个文件，站点写调用 %d 处，invalidate %d 处"
          % (root, scanned, writes, invalidations))
    if writes == 0:
        sys.stderr.write("一个站点写口都没数到 —— 判据没跑起来，不许读成“缓存一致”\n")
        return 2
    bad = []
    for rel, ln, snip in direct:
        bad.append("× %s:%d 直接调用全量站点接口（只允许 %s）\n    %s"
                   % (rel, ln, "/".join(sorted(ALLOWED)), snip))
    for rel, n in sorted(per_file_writes.items()):
        text = io.open(os.path.join(root, rel), encoding="utf-8", errors="replace").read()
        inv = text.count("stationDict.invalidate()")
        if inv < n:
            bad.append("× %s 有 %d 处站点写口，却只失效了 %d 次缓存 —— 改完站点别的页面还在念旧表"
                       % (rel, n, inv))
    for line in bad:
        print(line)
    if bad:
        print("❌ %d 处不达标" % len(bad))
        return 1
    print("✅ 全量站点字典只从共享缓存走，且 %d 处站点写口全部带失效" % writes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
