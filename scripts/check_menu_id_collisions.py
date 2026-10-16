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
    python scripts/check_menu_id_collisions.py --self-test     # 七把刀各占一个目录
退出码：0 = 没有跨文件撞号；1 = 有撞号（逐条列出）；2 = 扫描根不存在或一个候选都没数到
        （"数到 0 个"绝不能读成"没有问题"，那是判据没跑起来的形态）。
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
    argv = sys.argv[1:]
    if '--self-test' in argv:
        return run_self_test()
    args = [a for a in argv if a != '--report-only']
    report_only = '--report-only' in argv
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


# ---------------- 自检（七把刀，一条一个目录） ----------------
# 这本的历史坏法就是"两边各自都不报错、先落地的赢"，所以刀口对着**判据自己的两种写法**：
# VALUES 里的 `(<id>, ...` 与幂等脚本里的 `SELECT <id>, ...` 必须都认，只认一种就等于没认。

V_FORM = "INSERT INTO system_menu (id, name, permission) VALUES\n  (%s, '甲页', '%s');\n"
S_FORM = "INSERT INTO system_menu (id, name, permission)\nSELECT %s, '甲页', '%s' FROM dual;\n"


def _run(root):
    argv_bak = list(sys.argv)
    sys.argv = ['x', root]
    try:
        return main()
    finally:
        sys.argv = argv_bak


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix='menuid-')
    cases = [
        ('K1 两个文件用同一种写法认领同 id、permission 不同 ⇒ 判撞号',
         [V_FORM % ('6930', 'transport:a:query'), V_FORM % ('6930', 'transport:b:query')], 1),
        ('K2 同一文件内把同 id 写两遍不判撞号（那是它自己的事）',
         [V_FORM % ('6931', 'transport:a:query') + V_FORM % ('6931', 'transport:b:query')], 0),
        ('K3 跨文件同 id 且 permission 相同 ⇒ 复制粘贴，不判撞号',
         [V_FORM % ('6932', 'transport:a:query'), S_FORM % ('6932', 'transport:a:query')], 0),
        ('K4 两种写法各认一种就必须红（VALUES × SELECT 混着写也算撞号）',
         [V_FORM % ('6933', 'transport:a:query'), S_FORM % ('6933', 'transport:b:query')], 1),
        ('K5 VALUES 续行 `, (<id>, ...` 这一形也被认（漏了它候选数会少一片）',
         ['INSERT INTO system_menu (id, name, permission) VALUES\n  (6934, %s),\n  , (6935, %s);\n'
          % ("'甲', 'transport:a:query'", "'乙', 'transport:b:query'"),
          V_FORM % ('6935', 'transport:c:query')], 1),
    ]
    try:
        for i, (name, files, want) in enumerate(cases):
            d = os.path.join(tmp, 'k%d' % (i + 1))
            os.makedirs(d, exist_ok=True)
            for j, body in enumerate(files):
                with io.open(os.path.join(d, 'f%d.sql' % (j + 1)), 'w', encoding='utf-8', newline='\n') as f:
                    f.write(body)
            got = _run(d)
            if got != want:
                print('✘ %s：rc=%d（期望 %d）' % (name, got, want))
                rc = 1
            else:
                print('✔ %s（rc=%d）' % (name, got))
        # K6 一个主键声明都没数到 ⇒ rc=2，不许读成"没有撞号"
        d6 = os.path.join(tmp, 'k6')
        os.makedirs(d6, exist_ok=True)
        with io.open(os.path.join(d6, 'a.sql'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('-- 这个脚本不插菜单\nSELECT 1;\n')
        got6 = _run(d6)
        if got6 != 2:
            print('✘ K6 空样本 rc=%d（期望 2）' % got6)
            rc = 1
        else:
            print('✔ K6 数到 0 条主键声明走 rc=2 出口')
        # K7 真树
        if os.path.isdir('sql'):
            got7 = _run('sql')
            if got7 != 0:
                print('✘ K7 真树 rc=%d' % got7)
                rc = 1
            else:
                print('✔ K7 真树没有跨文件撞号（见上一行读数）')
        else:
            print('! K7 跳过：不在仓库根运行')
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == '__main__':
    sys.exit(main())
