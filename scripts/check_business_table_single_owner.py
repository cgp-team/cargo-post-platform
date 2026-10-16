#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""同一张业务表只许有一个出处（队列 #98 / 台账 D-136）。

要防的那件事：同一个业务对象被两条线各建一套。2026-10-16 真实发生的形状是
主线写 `V037__town_event_calendar.sql` 建 `transport_town_event_calendar`（带 recurrence），
并行的 AI 辅助线同一天写 `V050__ai_event_calendar.sql` 建 `town_event_calendar`（不带 recurrence），
两边各自的 DO / Mapper / Controller / 菜单全新一套。**表名不撞、菜单主键不撞**，
所以 `check_menu_id_collisions.py` 判绿 —— 那本闸认的是主键户口，不是业务身份。

为什么要单独一本闸：两套并存的代价不落在这次部署，落在运营身上。乡镇事件日历是
"赶集日提前留货舱"的唯一数据来源，两张表意味着同一件事填两遍、后台读一套、AI 提示读另一套，
而两边都念"这就是日历"。这类病本仓已经记过一笔同形的（#66：待调度单数同页两个答案）。

口径（只认这两条形，别的形状明确不认，写在下面 K4/K2）：
· 形状一：**去掉一个已知模块前缀后同名** ⇒ 判为同一个业务对象的两个出处。
  `transport_town_event_calendar` 与 `town_event_calendar` → 撞。
  `system_town_notice` 与 `transport_town_notice` → 不撞（比对是单向的：一方的裸名等于另一方的**全名**才算，
  两边都剥完再比会把上游 `system_*` 与自研 `transport_*` 整片误红）。
· 形状二：同名表出现在两个文件 ⇒ **不判**（本仓既有规矩：基线 `sql/mysql/transport-schema.sql` 与
  增量 `V018__multi_leg_framework.sql` 各写一遍同样的五张表，那是给全新库与存量库各留一条路，
  不是两套真值。实测在仓里有 5 对，全在这一条形下）。
· 形状三：同名 DO 简单类出现在两个顶层模块 ⇒ 判（`TownEventCalendarDO` 在 transport 与 ai-assist 各一份）。
· 明确不认的：剥完前缀只剩一个词的（`foo` vs `transport_foo`）—— 单名的误判面太大，
  这一格留给人读，K4 把这个"不认"钉成用例而不是口头承诺。

用法：python3 scripts/check_business_table_single_owner.py [--self-test] [仓库根]
退出码：0 = 无重复出处；1 = 有（逐条列 表名 / 两处文件）；2 = 扫描根不存在 或 一条 CREATE TABLE 都没数到
        （数到 0 绝不能读成"没有问题"——那是判据空转）。
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

CREATE_RE = re.compile(
    r'CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"]?([A-Za-z0-9_]+)[`"]?', re.I)
DO_PATH_RE = re.compile(r'[\\/]dal[\\/]dataobject[\\/]')
MODULE_PREFIXES = ("transport_", "system_", "infra_", "member_", "pay_")
# 至少两个词才比对（见 docstring 的"明确不认的"）
MIN_TOKENS = 2


def strip_comments(text):
    """按行去 `--` 尾注释，再整体去 `/* */` 块注释（DDL 里没有合法的 -- 字面量）。"""
    out = []
    for line in text.split("\n"):
        i = line.find("--")
        out.append(line if i < 0 else line[:i])
    joined = "\n".join(out)
    return re.sub(r'/\*.*?\*/', "", joined, flags=re.S)


def tables_in(path):
    text = strip_comments(io.open(path, encoding="utf-8", errors="replace").read())
    return [m.group(1).lower() for m in CREATE_RE.finditer(text)]


def tokens(name):
    return [t for t in name.split("_") if t]


def bare(name):
    """剥掉一个已知模块前缀后的名字；剥完不足两词就当作不可用（返回 None）。"""
    for p in MODULE_PREFIXES:
        if name.startswith(p):
            rest = name[len(p):]
            return rest if len(tokens(rest)) >= MIN_TOKENS else None
    return None


def scan_sql(root):
    """返回 (候选表声明总数, [(裸名, 全名A, 文件A, 全名B, 文件B)])。"""
    by_name = {}
    total = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d not in ("node_modules", ".git", "target")]
        for fn in sorted(filenames):
            if not fn.endswith(".sql"):
                continue
            path = os.path.join(dirpath, fn)
            for name in tables_in(path):
                total += 1
                by_name.setdefault(name, []).append(path)
    hits = []
    names = sorted(by_name)
    for a in names:
        ba = bare(a)
        if ba is None:
            continue
        for b in names:
            if a == b or b != ba:
                continue
            # 只报一次（a 是带前缀的那一方时才是本条形要抓的形状）
            hits.append((ba, a, sorted(by_name[a])[0], b, sorted(by_name[b])[0]))
    return total, hits, by_name


def scan_do(root):
    """同名 DO 出现在两个顶层模块 ⇒ 重复出处。返回 (扫到的 DO 文件数, [(类名, [模块...])])。"""
    by_cls = {}
    n = 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d not in ("node_modules", ".git", "target")]
        if not DO_PATH_RE.search(dirpath + os.sep):
            continue
        for fn in sorted(filenames):
            if not fn.endswith(".java"):
                continue
            n += 1
            cls = fn[:-5]
            top = os.path.relpath(dirpath, root).split(os.sep)[0].replace("/", os.sep)
            by_cls.setdefault(cls, set()).add(top)
    return n, [(c, sorted(ms)) for c, ms in sorted(by_cls.items()) if len(ms) > 1]


def run_scan(root):
    sql_root = os.path.join(root, "sql")
    if not os.path.isdir(sql_root):
        sys.stderr.write("扫描根下没有 sql/目录：%s\n" % sql_root)
        return 2
    total, hits, by_name = scan_sql(sql_root)
    do_total, do_hits = scan_do(root)
    print("[扫描] CREATE TABLE 声明 %d 条 / %d 个不同表名；DO 文件 %d 个 / 跨模块重名 %d 个；"
          "表名同物异形 %d 组" % (total, len(by_name), do_total, len(do_hits), len(hits)))
    if total == 0:
        sys.stderr.write("一条 CREATE TABLE 都没数到 —— 判据没跑起来，不许读成「没有重复」\n")
        return 2
    for bare_name, a, fa, b, fb in hits:
        print("× 同一个业务对象两张表：%s（剥前缀后同名 %s）\n    %s\n    %s"
              % (a, b, os.path.relpath(fa, root).replace(os.sep, "/"),
                 os.path.relpath(fb, root).replace(os.sep, "/")))
    for cls, mods in do_hits:
        print("× 同名 DO 落在两个模块：%s -> %s" % (cls, " / ".join(mods)))
    if hits or do_hits:
        print("❌ %d 组重复出处（两张表各填一遍的那种代价不落在这次部署，落在运营身上）"
              % (len(hits) + len(do_hits)))
        return 1
    print("✅ 没有同一业务对象的两张表 / 两个同名 DO")
    return 0


# ── 自检刀：每把钉一个真实形状，红的那一格必须是它该管的那一格 ──────────────────
V037 = ("CREATE TABLE IF NOT EXISTS `transport_town_event_calendar` (`id` bigint);\n"
        "INSERT INTO system_menu (id, name) VALUES (6950, '事件日历');\n")
V050 = ("CREATE TABLE IF NOT EXISTS `town_event_calendar` (`id` bigint);\n")


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix='biztable-')
    cases = [
        ('K1 剥前缀后同名的两张表 ⇒ 必须判重复出处（今天真发生在两条线上的形状）',
         {'sql/incremental/a.sql': V037, 'sql/incremental/b.sql': V050}, 1),
        ('K2 同名表写在基线与增量两处 ⇒ 不判（那是本仓给全新库/存量库各留一条路的既有规矩）',
         {'sql/mysql/schema.sql': V050, 'sql/incremental/v.sql': V050}, 0),
        ('K3 system_town_notice 与 transport_town_notice ⇒ 不判（比对是"一方剥完 == 另一方的全名"，'
         '两边都剥完再比会把上游 system_* 与自研 transport_* 整片误红）',
         {'sql/a.sql': "CREATE TABLE IF NOT EXISTS `system_town_notice` (`id` bigint);\n",
          'sql/b.sql': "CREATE TABLE IF NOT EXISTS `transport_town_notice` (`id` bigint);\n"}, 0),
        ('K4 剥完只剩一个词 ⇒ 明确不判（单名误判面太大；这一格是刻意的口径，不是漏）',
         {'sql/a.sql': "CREATE TABLE IF NOT EXISTS `transport_foo` (`id` bigint);\n",
          'sql/b.sql': "CREATE TABLE IF NOT EXISTS `foo` (`id` bigint);\n"}, 0),
        ('K5 被注释掉的 DDL 不参与（否则历史里随便一句注释就能把它点红）',
         {'sql/a.sql': "-- CREATE TABLE IF NOT EXISTS `transport_town_event_calendar` (`id` bigint);\n"
                       "/* CREATE TABLE `town_event_calendar` (`id` bigint); */\n",
          'sql/b.sql': "-- 只有一个建表语句被注掉，另一份都没有\n"}, 2),
        ('K6 同名 DO 落在两个顶层模块 ⇒ 判（表名换个写法躲得开，类名躲不开）',
         {'sql/a.sql': V037,
          'yudao-module-transport/src/main/java/x/dal/dataobject/dispatch/TownEventCalendarDO.java': "class D{}\n",
          'yudao-module-ai-assist/src/main/java/x/dal/dataobject/eventcalendar/TownEventCalendarDO.java': "class D{}\n"}, 1),
    ]
    try:
        for i, (name, files, want) in enumerate(cases):
            d = os.path.join(tmp, 'k%d' % (i + 1))
            for rel, body in files.items():
                p = os.path.join(d, *rel.split('/'))
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with io.open(p, 'w', encoding='utf-8', newline='\n') as f:
                    f.write(body)
            got = run_scan(d)
            if got == want:
                print("✔ %s（rc=%d，与预期一致）" % (name, got))
            else:
                print("✘ %s —— 期望 rc=%d，实跑 rc=%d" % (name, want, got))
                rc = 1
        # K7：真树的读数只报不钉 —— 此刻树里可能正躺着并行那一套，钉死会把它变成永远的红
        if os.path.isdir(os.path.join("sql", "incremental")):
            print("── K7 真树读数（不判红绿，只把现读数字留在这儿）")
            run_scan(".")
        else:
            print("! K7 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


def main(argv=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    if "--self-test" in argv:
        return run_self_test()
    args = [a for a in argv if not a.startswith("--")]
    root = args[0] if args else "."
    if not os.path.isdir(root):
        sys.stderr.write("扫描根不存在：%s\n" % root)
        return 2
    return run_scan(root)


if __name__ == "__main__":
    sys.exit(main())
