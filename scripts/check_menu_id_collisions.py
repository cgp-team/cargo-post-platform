#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""菜单主键撞号检查（队列 #70 / 台账 D-127）。

要防的那件事很具体：`sql/incremental/V032__pricing_rule_menu.sql` 与
`sql/mysql/transport-multi-leg-menu.sql` 都写了 6930/6931，而且**都自称幂等**。
两边各自都不报错，撞在一起的结果是"先落地的赢"：计价规则那一页在云上永远不存在，
CI 一片绿 —— 少东西比红难发现得多，所以这一格必须静态查，不能等部署。

口径：
· 只认"往 system_menu 里插固定主键"的那些行：`(<id>, '<名字>', '<permission>'` 这种三元组开头，
  以及 `SELECT <id>, '<名字>', '<permission>'` 这种幂等写法。两处都覆盖到才叫查过。
· 同一个 id 被两个**文件**认领、且 permission 不同 ⇒ 撞号，退出码 1。
· 同一文件内部重复（区间自增、或一份脚本分两段写同一块）不算撞号：那是它自己的事。

用法：
    python scripts/check_menu_id_collisions.py [扫描根，默认 sql/]
    python scripts/check_menu_id_collisions.py --report-only   # 只打表，不判红
退出码：0 = 没有跨文件撞号；1 = 有撞号（逐条列出）；2 = 扫描根不存在或一个候选都没数到
        （"数到 0 个"绝不能读成"没有问题"，那是判据没跑起来的形态）。
"""
import io
import os
import re
import sys

# 6940, '计价规则', 'transport:pricing-rule:query'   /   SELECT 6940, '计价规则', '...'
ROW = re.compile(
    r"(?<![\w.])(\d{3,6})\s*,\s*'([^']*)'\s*,\s*'(transport:[\w:.-]+|member:[\w:.-]+)'",
)
# 只在这些行上取词，避免把注释、SELECT 列表里的其它数字当成主键
INSERTY = re.compile(r"^\s*(\(\s*\d|\s*SELECT\s+\d|\s*,\s*\(\s*\d)", re.I)


def kt_files(root):
    for dirpath, _, files in os.walk(root):
        for fn in sorted(files):
            if fn.endswith('.sql'):
                yield os.path.join(dirpath, fn)


def main():
    args = [a for a in sys.argv[1:] if a != '--report-only']
    report_only = '--report-only' in sys.argv[1:]
    root = args[0] if args else 'sql'
    if not os.path.isdir(root):
        sys.stderr.write('扫描根不存在：%s\n' % root)
        return 2

    claims = {}          # id -> {permission: set(files)}
    seen_rows = 0
    for path in kt_files(root):
        rel = os.path.relpath(path, root).replace(os.sep, '/')
        try:
            lines = io.open(path, encoding='utf-8', errors='replace').read().split('\n')
        except OSError as e:
            sys.stderr.write('读不了 %s：%s\n' % (path, e))
            return 2
        for i, line in enumerate(lines, 1):
            stripped = line.strip()
            if stripped.startswith('--'):
                continue
            if not INSERTY.match(line):
                continue
            for m in ROW.finditer(line):
                mid, _name, perm = m.group(1), m.group(2), m.group(3)
                seen_rows += 1
                claims.setdefault(mid, {}).setdefault(perm, set()).add('%s:%d' % (rel, i))

    if seen_rows == 0:
        sys.stderr.write('一个菜单主键声明都没数到 —— 判据没跑起来，不许读成“没有撞号”\n')
        return 2

    print('[扫描] %s 下的 *.sql，共数到 %d 条主键声明，%d 个不同的 id'
          % (root, seen_rows, len(claims)))

    bad = 0
    for mid in sorted(claims, key=lambda x: int(x)):
        perms = claims[mid]
        files = {f for s in perms.values() for f in s}
        owner_files = {p: sorted({f.split(':')[0] for f in s}) for p, s in perms.items()}
        if len(perms) > 1 and len({f for s in owner_files.values() for f in s}) > 1:
            bad += 1
            print('× id=%s 被 %d 个 permission 认领：%s' % (mid, len(perms), owner_files))
            for p, fs in sorted(owner_files.items()):
                print('    %s  <- %s' % (p, ', '.join(fs)))
        elif len(files) > 1:
            print('  （同一 id 在同义反复地被多文件使用，permission 相同 ⇒ 判为复制粘贴，不算撞号）'
                  ' id=%s files=%s' % (mid, sorted({f.split(':')[0] for f in files})))

    if bad == 0:
        print('✅ 没有跨文件的菜单主键撞号')
        return 0
    print('❌ %d 个 id 被两个以上文件认领' % bad)
    return 0 if report_only else 1


if __name__ == '__main__':
    sys.exit(main())
