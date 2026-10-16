#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SQL 函数参数量检查（台账 D-127，由队列 #69/#70 那一轮实测带出）。

要防的那件事很具体：`.github/workflows/db-migrate-guarded.yml` 的只读探测步骤里写了
`IF(COUNT(*)=3, '已存在 3/3', '缺失（当前 ', COUNT(*), '/3）')` —— 五个参。MySQL 的 `IF()` 只收
三个（`IF(expr1, expr2, expr3)`），第四个逗号直接 1064。CONCAT 是变元的，所以这个错只在 IF 上犯，
而抄格式的人会连错误一起抄：两条 pre-existing 的探测加一条新写的探测，三处同形。

后果比"日志少一行"大得多：探测步骤排在 apply 步骤前面，它一红整条 job 就停，
后面那条**唯一的写库路径根本没被执行到** —— 而这份文件的用意恰恰是"写库只有这一个入口"。

口径：
· 只查 `IF(` 与 `IFNULL(` 的顶层逗号数（上限 3 / 2）。CONCAT / CONCAT_WS / IN 不管，它们是变元的。
· 扫 `.sql` / `.sh` / `.yml` / `.yaml`，按整段文本配对括号（多行调用照样数得出来），大小写都认。
  bash 的 `if (子命令)` 也会被数到，但它永远 ≤3 个顶层逗号，不会造成误红；宁可多扫不误判。
· 起始行是注释（SQL 的 `--`、shell/YAML 的 `#`）的调用跳过：写在注释里的反例不是待执行的 SQL。
· 一个候选调用都没数到 ⇒ 退出码 2：**"数到 0"绝不能读成"没有问题"**，那是判据没跑起来的形态。

用法：
    python3 scripts/check_sql_function_arity.py                  # 扫默认根
    python3 scripts/check_sql_function_arity.py --self-test      # 先自证能红，再扫树
    python3 scripts/check_sql_function_arity.py --root sql --root .github/workflows
退出码：0 = 干净；1 = 有超参调用（逐条列出 文件:行）；2 = 扫描根不存在 / 读不了 / 候选数为 0 / 自测没过。
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

MAX_ARGS = {"IF": 3, "IFNULL": 2}
SUFFIXES = (".sql", ".sh", ".yml", ".yaml")
DEFAULT_ROOTS = ("sql", ".github/workflows", "scripts", "deploy")
PRUNE = {".git", "node_modules", "target", "tmp", ".gradle", ".venv", "android-app"}
# 起始行为注释则跳过（注释里的反例不是待执行的 SQL）
COMMENT = re.compile(r"^\s*(--|#|/\*|\*)")


def split_top(args):
    """按顶层逗号切分实参：嵌套括号与字符串字面量里的逗号不算。"""
    parts, cur, depth, instr, esc = [], "", 0, None, False
    for ch in args:
        if esc:
            cur += ch
            esc = False
            continue
        if ch == "\\" and instr:
            cur += ch
            esc = True
            continue
        if instr:
            cur += ch
            if ch == instr:
                instr = None
            continue
        if ch in "'\"":
            instr = ch
            cur += ch
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
            continue
        cur += ch
    parts.append(cur)
    return [p for p in parts if p.strip() != ""]


def scan_text(text, rel):
    """返回 (超参列表, 候选调用数)。"""
    bad, seen = [], 0
    lines = text.split("\n")
    for name, limit in MAX_ARGS.items():
        for m in re.finditer(r"\b" + name + r"\s*\(", text, re.I):
            depth, j, n = 1, m.end(), len(text)
            while j < n and depth:
                if text[j] == "(":
                    depth += 1
                elif text[j] == ")":
                    depth -= 1
                j += 1
            if depth:
                continue  # 括号不配对（被截断或不是 SQL），不在本闸口径内
            line_no = text.count("\n", 0, m.start()) + 1
            if COMMENT.match(lines[line_no - 1]):
                continue
            seen += 1
            args = split_top(text[m.end():j - 1])
            if len(args) > limit:
                snip = " ".join(lines[line_no - 1].strip().split())[:110]
                bad.append((rel, line_no, name, len(args), limit, snip))
    return bad, seen


def files_under(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d not in PRUNE]
        for fn in sorted(filenames):
            if fn.endswith(SUFFIXES):
                out.append(os.path.join(dirpath, fn))
    return out


def check_roots(roots):
    bad, seen, nfiles = [], 0, 0
    for root in roots:
        if not os.path.isdir(root):
            sys.stderr.write("扫描根不存在或不是目录：%s\n" % root)
            return None, None, None
        for path in files_under(root):
            try:
                text = io.open(path, encoding="utf-8", errors="replace").read()
            except OSError as e:
                sys.stderr.write("读不了 %s：%s\n" % (path, e))
                return None, None, None
            nfiles += 1
            b, s = scan_text(text, os.path.relpath(path).replace(os.sep, "/"))
            bad += b
            seen += s
    return bad, seen, nfiles


def self_test():
    """负控：同一套机制必须能让注入的红起来。红不了就是判据在空转。"""
    cases = [
        ("5 参 IF", "SELECT CONCAT(IF(COUNT(*)=3,'a','b',COUNT(*),'c'),'d') FROM t;", 1),
        ("3 参 IF", "SELECT IF(COUNT(*)=3,'a','b') FROM t;", 0),
        ("2 参 IFNULL", "SELECT IFNULL(a,'x') FROM t;", 0),
        ("3 参 IFNULL", "SELECT IFNULL(a,'x','y') FROM t;", 1),
        ("注释里的反例", "-- IF(a,'b','c','d','e')\nSELECT 1;", 0),
        ("多行调用", "SELECT IF(\n  a=1,\n  'b',\n  'c',\n  'd'\n) FROM t;", 1),
        ("字符串里的逗号不算", "SELECT IF(a,'b,c','d') FROM t;", 0),
        ("嵌套括号里的逗号不算", "SELECT IF(a IN (1,2,3),'b','d') FROM t;", 0),
    ]
    failed = 0
    for label, sql, expect in cases:
        bad, seen = scan_text(sql, "<self-test>")
        got = 1 if bad else 0
        mark = "✔" if got == expect else "✘"
        if got != expect:
            failed += 1
        print("  %s %s：期望红=%d 实得红=%d（数到 %d 个候选调用）" % (mark, label, expect, got, seen))
    if failed:
        sys.stderr.write("自测 %d 条不符 —— 判据本身不可信，拒绝当作通过\n" % failed)
        return 2
    return 0


def main():
    argv = sys.argv[1:]
    if "--self-test" in argv:
        rc = self_test()
        if rc:
            return rc

    roots, i = [], 0
    while i < len(argv):
        if argv[i] == "--root" and i + 1 < len(argv):
            roots.append(argv[i + 1])
            i += 2
            continue
        i += 1
    if not roots:
        roots = list(DEFAULT_ROOTS)

    bad, seen, nfiles = check_roots(roots)
    if bad is None:
        return 2
    print("[扫描] %s ⇒ %d 个文件，数到 %d 个 IF/IFNULL 调用" % (", ".join(roots), nfiles, seen))
    if seen == 0:
        sys.stderr.write("一个候选调用都没数到 —— 判据没跑起来，不许读成“没有超参”\n")
        return 2
    if not bad:
        print("✅ %d 个 IF/IFNULL 调用的参数量都在上限内" % seen)
        return 0
    for rel, line_no, name, got, limit, snip in bad:
        print("× %s:%d  %s() 给了 %d 个参（上限 %d）\n    %s" % (rel, line_no, name, got, limit, snip))
    print("❌ %d 处超参：%d 处一旦真跑就是 MySQL 1064" % (len(bad), len(bad)))
    return 1


if __name__ == "__main__":
    sys.exit(main())
