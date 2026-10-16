#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""首页快捷入口 / 卡片跳转的路由必须真的落在一个有组件的菜单叶子上（队列 #63 / 台账 D-130）。

要防的那件事：运营概览的六个快捷入口 push 的是 `/transport/station` 这种**目录节点**
（`system_menu` 里 type=1、component=NULL），真正带页面的叶子是 `/transport/station/list`（6811）。
点下去路由匹配到了父级 Layout，子节点为空 ⇒ 白屏一格，页面不报错、控制台也不一定吐东西。
仓库里两侧不同形是**确定的事实**；线上 `system_menu` 是否等于 SQL 是另一回事（那要连库读，本机没有客户端）。

口径：
· 从 `sql/**/*.sql` 里把 `system_menu` 的固定主键行读出来（id, name, permission, type, sort, parent_id, path, …），
  按 parent_id 递归拼出完整路径（父 `/transport` + 子 `station` + 孙 `list` ⇒ `/transport/station/list`）。
· "可跳转"只认 **type=2 且 component 非空** 的那些节点（目录 type=1 与按钮 type=3 都不算）。
· 扫 `views/transport/**/*.vue` 里写死的 `path: '/…'` 字面量（看板的六个快捷入口、调度中心的跳拓扑…），
  逐个对照上面这个集合。注释行（`//` 开头）不算待执行的跳转。
· 数到 0 行菜单或数到 0 个字面量 ⇒ 退出码 2：那是判据没跑起来，不能读成"没有问题"。

用法：python3 scripts/check_admin_dashboard_routes.py
退出码：0 = 都能落到页面上；1 = 有跳空的入口（逐条列出，并给出建议的叶子路径）；2 = 判据没跑起来。
"""

# 输出流强制 UTF-8（GBK 控制台上不这么做，打印中文时抛 UnicodeEncodeError，
# rc=1 会被读成「这一本判红」，而实际是判据一次都没判）
import sys as _sys
for _s in (_sys.stdout, _sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
import io
import os
import re
import sys

SQL_ROOT = "sql"
VIEWS_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views", "transport")
DASHBOARD = os.path.join(VIEWS_ROOT, "dashboard", "index.vue")
# (6811, '站点列表', 'transport:station:query', 2, 1, 6810, 'list', '', 'transport/station/index', ...)
#   最后一项要同时认 `NULL`（目录节点 type=1 就是这么写的）与引号字符串 ——
#   第一版只认引号，于是所有目录行都没进来：拼路径时父级缺失，连本来正确的
#   `/transport/monitoring/map` 都被误报成"菜单里根本没有"。
ROW = re.compile(
    r"^\s*\(?\s*(\d{3,6})\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*(\d)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*(?:'([^']*)'|NULL)",
)
LITERAL = re.compile(r"path:\s*'(/[^']*)'")


def load_menu_rows():
    rows = {}
    for dirpath, dirnames, filenames in os.walk(SQL_ROOT):
        dirnames[:] = [d for d in sorted(dirnames) if d not in (".git", "node_modules")]
        for fn in sorted(filenames):
            if not fn.endswith(".sql"):
                continue
            path = os.path.join(dirpath, fn)
            for line in io.open(path, encoding="utf-8", errors="replace").read().split("\n"):
                if line.strip().startswith("--"):
                    continue  # 注释里的例子不是菜单行
                m = ROW.match(line)
                if not m:
                    continue
                mid, name, _perm, mtype, _sort, parent, mp = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6), m.group(7)
                comp = m.group(9) or ""
                rows[int(mid)] = dict(name=name, type=int(mtype), parent=int(parent),
                                      path=mp, component=comp, file=os.path.relpath(path).replace(os.sep, "/"))
    return rows


def join(parent_path, child_path):
    if child_path.startswith("/"):
        return child_path
    if not parent_path:
        return "/" + child_path
    return parent_path.rstrip("/") + "/" + child_path


def full_paths(rows):
    def resolve(mid, seen=None):
        seen = seen or set()
        r = rows[mid]
        if r["parent"] == 0 or r["parent"] not in rows or r["parent"] in seen:
            return "/" + r["path"].lstrip("/") if r["path"] else ""
        return join(resolve(r["parent"], seen | {mid}), r["path"])

    pages, dirs = set(), set()
    for mid, r in rows.items():
        fp = resolve(mid)
        if not fp:
            continue
        if r["type"] == 2 and r["component"]:
            pages.add(fp)
        if r["type"] == 1:
            dirs.add(fp)
    return pages, dirs


def main():
    if not os.path.isdir(SQL_ROOT) or not os.path.isdir(VIEWS_ROOT):
        sys.stderr.write("扫描根不存在（sql 或 transport 视图目录）：判据没跑起来\n")
        return 2
    rows = load_menu_rows()
    if not rows:
        sys.stderr.write("一条 system_menu 固定主键行都没数到 —— 不许读成“跳转都对”\n")
        return 2
    pages, dirs = full_paths(rows)
    # 扫整个 transport 域：写死的 route 字面量不止看板有（调度中心也会 push 到拓扑页），
    # 只盯一个文件就等于给其余页面留了口子
    literals, scanned_files = [], 0
    for dirpath, dirnames, filenames in os.walk(VIEWS_ROOT):
        dirnames[:] = [d for d in sorted(dirnames) if d != "__tests__"]
        for fn in sorted(filenames):
            if not fn.endswith(".vue"):
                continue
            rel = os.path.relpath(os.path.join(dirpath, fn), ".").replace(os.sep, "/")
            scanned_files += 1
            for i, line in enumerate(io.open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace").read().split("\n"), 1):
                if line.strip().startswith(("//", "*")):
                    continue  # 注释里的示例不是待执行的跳转
                for p in LITERAL.findall(line):
                    literals.append((rel, i, p))
    if not literals:
        sys.stderr.write("transport 域里一个 path 字面量都没数到 —— 判据没跑起来\n")
        return 2
    print("[扫描] system_menu 行 %d 条 ⇒ 可跳转叶子 %d 个，目录节点 %d 个；"
          "%d 个页面里数到 path 字面量 %d 处"
          % (len(rows), len(pages), len(dirs), scanned_files, len(literals)))
    bad = []
    for rel, ln, p in literals:
        if p in pages:
            continue
        if p in dirs:
            kids = sorted([q for q in pages if q.startswith(p.rstrip("/") + "/")])
            bad.append((rel, ln, p, "这是目录节点（type=1，component=NULL），点了是空 Layout；候选叶子：%s"
                        % ("、".join(kids[:3]) or "无")))
        else:
            bad.append((rel, ln, p, "菜单里根本没有这个路径"))
    for rel, ln, p, why in bad:
        print("× %s:%d  path='%s' —— %s" % (rel, ln, p, why))
    if bad:
        print("❌ %d 处跳转落不到页面上（共 %d 处）" % (len(bad), len(literals)))
        return 1
    print("✅ %d 处写死跳转全部落在有组件的菜单叶子上" % len(literals))
    return 0


if __name__ == "__main__":
    sys.exit(main())
