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
# 菜单行有两种写法，都必须认：
#   A：`VALUES (6801, '运营概览', 'transport:dashboard:query', 2, 1, 6800, 'dashboard', '…', 'transport/dashboard/index', …)`
#   B：`INSERT INTO system_menu (...) SELECT 7100, 'AI 辅助', '', 1, 21, 0, 'ai', '…', NULL, … FROM DUAL WHERE NOT EXISTS (…)`
# 第一版只认 A 的**单行**形状：于是 B 这种折行写的幂等脚本整片没进集合，
# 拼路径时父级缺失，连本来正确的 `/transport/monitoring/map` 都被误报成"菜单里根本没有"，
# 而 `/ai/**` 那五个页面 + 钱包/计价/日历三个页面**对判据完全隐形**（它念"叶子 21 个"，真实是 30 个）。
# 认形状不认语义就是这本的历史坏法 —— 改成按语句展平后再扫，两种形状共用一个正则。
ROW = re.compile(
    r"(?:\(\s*|(?:VALUES|SELECT)\s+)(\d{3,6})\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*(\d)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*(?:'([^']*)'|NULL)",
)
LITERAL = re.compile(r"path:\s*'(/[^']*)'")


def load_menu_rows(root=None):
    """从 sql/ 里把 system_menu 的固定主键行读出来（两种写法都认）。"""
    rows = {}
    base = os.path.join(root, SQL_ROOT) if root else SQL_ROOT
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in sorted(dirnames) if d not in (".git", "node_modules")]
        for fn in sorted(filenames):
            if not fn.endswith(".sql"):
                continue
            path = os.path.join(dirpath, fn)
            if "ruoyi-vue-pro" in fn:
                # 上游 yudao 的全量 schema（几千条 system_menu 行）。旧版因为正则把行首锚死了，
                # 这些行**恰好一条都没进来**，于是这本一直在数"本业务线的菜单面"，只是它没说出来。
                # 现在显式排除并把话说清：这一本判的是 transport/ai 域的跳转，把上游面并进来
                # 会让念出来的叶子数涨一个数量级，而判的还是同一批字面量。
                continue
            text = io.open(path, encoding="utf-8", errors="replace").read()
            # 注释行里的例子不是菜单行；整句折行先展平成一个空格，才认得 B 那种写法
            text = "\n".join(l for l in text.split("\n") if not l.strip().startswith("--"))
            for stmt in text.split(";"):
                if "system_menu" not in stmt:
                    continue
                flat = " ".join(stmt.split())
                for m in ROW.finditer(flat):
                    mid = int(m.group(1))
                    rec = dict(name=m.group(2), type=int(m.group(4)), parent=int(m.group(6)),
                               path=m.group(7), component=m.group(9) or "",
                               file=os.path.relpath(path, root or ".").replace(os.sep, "/"))
                    rows.setdefault(mid, rec)
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
    root = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "."
    sql_root = SQL_ROOT if root == "." else os.path.join(root, SQL_ROOT)
    views_root = VIEWS_ROOT if root == "." else os.path.join(root, VIEWS_ROOT)
    if not os.path.isdir(sql_root) or not os.path.isdir(views_root):
        sys.stderr.write("扫描根不存在（sql 或 transport 视图目录）：判据没跑起来\n")
        return 2
    rows = load_menu_rows(None if root == "." else root)
    if not rows:
        sys.stderr.write("一条 system_menu 固定主键行都没数到 —— 不许读成“跳转都对”\n")
        return 2
    pages, dirs = full_paths(rows)
    # 扫整个 transport 域：写死的 route 字面量不止看板有（调度中心也会 push 到拓扑页），
    # 只盯一个文件就等于给其余页面留了口子
    literals, scanned_files = [], 0
    for dirpath, dirnames, filenames in os.walk(views_root):
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


# ---------------- 自检（五把刀）----------------
# 这本闸的历史坏法有两种，刀口分别对着它们：
#   ① 只认 `VALUES (…)` 的单行形状 ⇒ 幂等脚本那种 `INSERT … SELECT`（还会折行）整片读不到，
#      于是它念"叶子 21 个"而真实是 30 个 —— 少读的那九个里包含整个 `/ai/**`。
#      K2/K3 是一对：K2 必须绿、K3 必须红，否则 K2 的绿不知道是谁给的。
#   ② 数到 0 却报"全部落在页面上" ⇒ K4/K5 钉住两个 fail-closed 出口。

SQL_VALUES_BASE = """
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name) VALUES
  (6800, '客货邮管理', '', 1, 5, 0, '/transport', 'ep:ship', NULL, NULL),
  (6810, '站点管理', '', 1, 2, 6800, 'station', 'ep:location', NULL, NULL),
  (6811, '站点列表', 'transport:station:query', 2, 1, 6810, 'list', '', 'transport/station/index', 'TransportStation');
"""

# 真实来源的形状（V057 那一支就这么写：列清单换行、SELECT 换行、值再换行）
SQL_SELECT_FORM = """
INSERT INTO system_menu (id, name, permission, type, sort, parent_id, path, icon, component, component_name,
                         status, visible, keep_alive, always_show, creator, create_time, updater, update_time, deleted)
SELECT 7160, '运力资源', '', 2, 2, 6800, 'capacity', 'ep:van',
       'transport/capacity/index', 'TransportCapacityWorkbench',
       0, b'1', b'1', b'1', '1', NOW(), '1', NOW(), b'0'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id = 7160);
"""

VUE_TO_CAPACITY = "<template><div/></template>\n<script setup>const go = () => router.push({ path: '/transport/capacity' })</script>\n"
VUE_TO_DIR = "<template><div/></template>\n<script setup>const go = () => router.push({ path: '/transport/station' })</script>\n"
VUE_NO_LITERAL = "<template><div/></template>\n<script setup>const a = 1</script>\n"


def _mkcase(tmp, name, sql_bodies, vue_bodies):
    d = os.path.join(tmp, name)
    os.makedirs(os.path.join(d, SQL_ROOT), exist_ok=True)
    os.makedirs(os.path.join(d, VIEWS_ROOT), exist_ok=True)
    for i, body in enumerate(sql_bodies):
        with io.open(os.path.join(d, SQL_ROOT, "f%d.sql" % (i + 1)), "w", encoding="utf-8", newline="\n") as f:
            f.write(body)
    for i, body in enumerate(vue_bodies):
        with io.open(os.path.join(d, VIEWS_ROOT, "v%d.vue" % (i + 1)), "w", encoding="utf-8", newline="\n") as f:
            f.write(body)
    return d


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix="dashroute-")
    cases = [
        ("K1 指到目录节点（type=1、component=NULL）⇒ 判红【旧坏法】",
         [SQL_VALUES_BASE], [VUE_TO_DIR], 1),
        ("K2 `INSERT … SELECT` 折行形状的叶 ⇒ 认得，判绿【形状刀·正对照】",
         [SQL_VALUES_BASE, SQL_SELECT_FORM], [VUE_TO_CAPACITY], 0),
        ("K3 同一句去掉那条 SELECT 形状 ⇒ 必须红（K2 的绿是这条语句给的，不是空转）",
         [SQL_VALUES_BASE], [VUE_TO_CAPACITY], 1),
        ("K4 一条菜单行都没数到 ⇒ rc=2，不许读成“跳转都对”",
         ["-- 这个脚本不插菜单\nSELECT 1;\n"], [VUE_TO_CAPACITY], 2),
        ("K5 一个 path 字面量都没数到 ⇒ rc=2（视图根空转）",
         [SQL_VALUES_BASE], [VUE_NO_LITERAL], 2),
    ]
    try:
        for name, sqls, vues, want in cases:
            d = _mkcase(tmp, re.sub(r"\W+", "_", name), sqls, vues)
            argv_bak = list(sys.argv)
            sys.argv = ["x", d]
            try:
                got = main()
            finally:
                sys.argv = argv_bak
            if got != want:
                print("✘ %s：rc=%s（期望 %s）" % (name, got, want))
                rc = 1
            else:
                print("✔ %s（rc=%s）" % (name, got))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        sys.exit(run_self_test())
    sys.exit(main())
