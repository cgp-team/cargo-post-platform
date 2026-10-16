#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 16 本判据（**本机专用**：要一个能连的 MySQL，CI 里没有 ⇒ 不进 ci.yml 的 15 本静态闸）。

它要回答的只有一个问题：**guarded 的 apply 白名单跑第二遍会不会出事？**
这个问题必须在点 apply 之前回答，因为 `.github/workflows/db-migrate-guarded.yml` 的 apply
每次都把整份白名单重跑一遍，而云端那台机器**用户自己都管不到**、出错只能看 CI 日志。

做法（不碰在用的库）：
  1. 开一次性库 → 照 `sql/mysql/` 的基线建好（与云上同一套 DDL）；
  2. 白名单跑第 1 遍，逐表精确 COUNT(*) 拍照；
  3. 白名单跑第 2 遍，再拍照 —— **任何一张表行数变了就是不幂等**（脚本失败另算）；
  4. 把工作流里那 32 格 `probe_strict` 的原句在这个库上跑一遍，逐格对期望值
     ⇒ 顺带验了"探针写的是不是它以为在验的那件事"；
  5. DROP 一次性库。

判据口径：
  rc=0 两遍都干净、零漂移、32 格全对；
  rc=1 有脚本失败 / 有行数漂移 / 有探针对不上（红因逐条打印）；
  rc=2 **拒判**：找不到 mysql 客户端、连不上、读不到口令、基线文件缺失、
       或从工作流里抽出来的白名单/探针数目与在册不符（15/32）—— 抽不到就不许报绿。

本轮立案的实测（2026-10-16）：改之前第 2 遍 V037 直接 `ERROR 1061 Duplicate key name
'idx_town_lookup'` 中断，`system_role_menu` 466 → 481（+15 行），6 格探针从 1 变 2。
根因是 `system_role_menu` 只有自增主键、**没有 (role_id, menu_id) 唯一键**，
所以基线那种 `INSERT IGNORE` 挡不住重复执行。
"""
import io
import os
import re
import shutil
import subprocess
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WF = os.path.join(ROOT, ".github", "workflows", "db-migrate-guarded.yml")
LOCAL_YAML = os.path.join(ROOT, "yudao-server", "src", "main", "resources", "application-local.yaml")
SCRATCH = "buspost_mig_idem_check"
EXPECT_WHITELIST = 15   # V029 不在册（那份迁移被明确排除）
EXPECT_PROBES = 32

BASELINE = [
    "sql/mysql/ruoyi-vue-pro.sql",
    "sql/mysql/transport-schema.sql",
    "sql/mysql/transport-schema-incremental.sql",
    "sql/mysql/transport-menu.sql",
    "sql/mysql/transport-multi-leg-menu.sql",
    "sql/mysql/cleanup-upstream-menus.sql",
]


def die(msg, rc=2):
    print("✘ 拒判：%s" % msg)
    sys.exit(rc)


def find_mysql():
    env = os.environ.get("MIGCHECK_MYSQL")
    if env:
        return env
    for cand in (r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
                 r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe",
                 "/usr/bin/mysql", "/usr/local/bin/mysql"):
        if os.path.exists(cand):
            return cand
    return shutil.which("mysql") or ""


def read_creds():
    user = os.environ.get("MIGCHECK_USER")
    pwd = os.environ.get("MIGCHECK_PWD")
    if user and pwd:
        return user, pwd
    if not os.path.exists(LOCAL_YAML):
        die("读不到 %s（本机库口令在那里）" % os.path.relpath(LOCAL_YAML, ROOT))
    txt = io.open(LOCAL_YAML, encoding="utf-8").read()
    m = re.search(r"username:\s*(\S+)\s*\n\s*password:\s*(\S+)", txt)
    if not m:
        die("application-local.yaml 里没读到 username/password（不猜、不用空口令试）")
    return m.group(1), m.group(2)


class Db(object):
    def __init__(self, mysql, user, pwd):
        self.cmd = [mysql, "-u", user, "-h", "127.0.0.1", "--default-character-set=utf8mb4"]
        self.env = dict(os.environ)
        self.env["MYSQL_PWD"] = pwd   # 口令只走环境变量：不进命令行、不进日志

    def q(self, sql, db=None):
        cmd = self.cmd + ([db] if db else [])
        p = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True, env=self.env, cwd=ROOT)
        return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")

    def file(self, path, db=None):
        cmd = self.cmd + ([db] if db else [])
        with open(path, "rb") as f:
            p = subprocess.run(cmd, stdin=f, capture_output=True, env=self.env, cwd=ROOT)
        return p.returncode, p.stderr.decode("utf-8", "replace").strip()

    def scalar(self, sql, db=None):
        rc, out, err = self.q(sql, db)
        if rc != 0:
            return None, err.strip().split("\n")[0][:160]
        lines = [l for l in out.strip().split("\n") if l.strip()]
        if len(lines) < 2:
            return None, "没有数据行"
        return lines[1].split("\t")[0].strip(), None


def extract():
    """从工作流里抽白名单与 probe_strict 原句；数目对不上就拒判（抽取器失效不许报绿）。"""
    if not os.path.exists(WF):
        die("找不到 %s" % os.path.relpath(WF, ROOT))
    wf = io.open(WF, encoding="utf-8").read()
    if "for f in" not in wf:
        die("工作流里没有 `for f in` 那段（apply 白名单的形状变了，先改本文件的抽取器）")
    seg = wf[wf.index("for f in"):]
    seg = seg[:seg.index("; do")]
    names = list(dict.fromkeys(re.findall(r"V0\d{2}__[a-z_0-9]+\.sql", seg)))
    probes = re.findall(r'probe_strict\s+"([^"]+)"\s+(\d+)\s+"((?:[^"]|\n)*?)"\s*\n', wf)
    if len(names) != EXPECT_WHITELIST:
        die("白名单抽到 %d 个，在册 %d 个（同轮改本文件顶部的 EXPECT_WHITELIST 并写原因）"
            % (len(names), EXPECT_WHITELIST))
    if len(probes) != EXPECT_PROBES:
        die("probe_strict 抽到 %d 格，在册 %d 格（同轮改 EXPECT_PROBES 并写原因）"
            % (len(probes), EXPECT_PROBES))
    return ["sql/incremental/" + n for n in names], probes


def snapshot(db):
    rc, out, err = db.q("SELECT TABLE_NAME FROM information_schema.TABLES "
                        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", SCRATCH)
    if rc != 0:
        die("取不到表清单：%s" % err[:160])
    names = [l.strip() for l in out.strip().split("\n")[1:] if l.strip()]
    counts = {}
    for n in names:
        v, e = db.scalar("SELECT COUNT(*) FROM `%s`" % n, SCRATCH)
        counts[n] = "ERR:" + e if e else v
    return counts


def run_real(db, whitelist, probes, quiet=False):
    """返回 (红因列表, 现读摘要)。"""
    red = []
    db.q("DROP DATABASE IF EXISTS `%s`" % SCRATCH)
    rc, _, err = db.q("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" % SCRATCH)
    if rc != 0:
        die("建不了一次性库（连不上 MySQL 或没有建库权限）：%s" % err.strip().split("\n")[0][:160])
    try:
        for b in BASELINE:
            p = os.path.join(ROOT, b)
            if not os.path.exists(p):
                die("基线文件不存在：%s（少一份基线，探针对不上就是假红）" % b)
            rc, err = db.file(p, SCRATCH)
            if rc != 0:
                red.append("基线 %s 执行失败：%s" % (b, err.split("\n")[0][:140]))
        snaps = {}
        for pass_no in (1, 2):
            for s in whitelist:
                rc, err = db.file(os.path.join(ROOT, s), SCRATCH)
                if rc != 0:
                    red.append("第 %d 遍 %s 失败：%s" % (pass_no, os.path.basename(s), err.split("\n")[0][:140]))
            snaps[pass_no] = snapshot(db)
        drift = [(t, snaps[1].get(t), snaps[2].get(t))
                 for t in sorted(snaps[2]) if snaps[2].get(t) != snaps[1].get(t)]
        for t, a, b in drift:
            red.append("第二遍把 %s 从 %s 行写成 %s 行（这一句不幂等）" % (t, a, b))
        bad_probe = []
        for label, want, sql in probes:
            got, err = db.scalar(" ".join(sql.split()), SCRATCH)
            if err:
                bad_probe.append((label, want, "ERR:" + err))
            elif str(got) != str(want):
                bad_probe.append((label, want, got))
        for label, want, got in bad_probe:
            red.append("探针「%s」期望 %s 实读 %s" % (label, want, got))
        summary = "白名单 %d 个跑两遍；漂移 %d 张表；探针 %d/%d 格对得上" % (
            len(whitelist), len(drift), len(probes) - len(bad_probe), len(probes))
        return red, summary
    finally:
        db.q("DROP DATABASE IF EXISTS `%s`" % SCRATCH)


# ── 自检刀口：判据自己得能红 ────────────────────────────────────────────────
FIX_BAD = "CREATE TABLE IF NOT EXISTS `fix_bad` (`id` INT);\nINSERT INTO `fix_bad` VALUES (1);\n"
FIX_GOOD = ("CREATE TABLE IF NOT EXISTS `fix_good` (`id` INT);\n"
            "INSERT INTO `fix_good` (`id`) SELECT 1 FROM DUAL\n"
            "  WHERE NOT EXISTS (SELECT 1 FROM `fix_good` WHERE `id` = 1);\n")


def self_test():
    whitelist, probes = extract()
    print("✔ K1 白名单抽到 %d 个（在册 %d）" % (len(whitelist), EXPECT_WHITELIST))
    print("✔ K2 probe_strict 抽到 %d 格（在册 %d）" % (len(probes), EXPECT_PROBES))

    mysql = find_mysql()
    if not mysql or not os.path.exists(mysql):
        die("找不到 mysql 客户端（本判据要一个能连的库，不许空转报绿）")
    print("✔ K3 mysql 客户端在：%s" % os.path.basename(mysql))
    user, pwd = read_creds()
    db = Db(mysql, user, pwd)

    # K4：故意不幂等的一句 ⇒ 必须被数出来（否则这本判据就是摆设）
    tmpdir = os.path.join(ROOT, "tmp", "idem_fixture")
    os.makedirs(tmpdir, exist_ok=True)
    bad = os.path.join(tmpdir, "fix_bad.sql")
    good = os.path.join(tmpdir, "fix_good.sql")
    io.open(bad, "w", encoding="utf-8", newline="\n").write(FIX_BAD)
    io.open(good, "w", encoding="utf-8", newline="\n").write(FIX_GOOD)

    db.q("DROP DATABASE IF EXISTS `%s`" % SCRATCH)
    rc, _, err = db.q("CREATE DATABASE `%s` DEFAULT CHARACTER SET utf8mb4" % SCRATCH)
    if rc != 0:
        die("建不了一次性库：%s" % err.strip().split("\n")[0][:160])
    try:
        snaps = []
        for _round in (1, 2):
            for f in (bad, good):
                r, e = db.file(f, SCRATCH)
                if r != 0:
                    die("自检的 fixture 都跑不动：%s" % e.split("\n")[0][:140])
            snaps.append(snapshot(db))
        grew = int(snaps[1]["fix_bad"]) > int(snaps[0]["fix_bad"])
        stayed = snaps[1]["fix_good"] == snaps[0]["fix_good"] == "1"
        print("%s K4 非幂等 fixture 第二遍行数变多 ⇒ 判据看得见（%s → %s）"
              % ("✔" if grew else "✘", snaps[0]["fix_bad"], snaps[1]["fix_bad"]))
        print("%s K5 幂等 fixture 两遍都是 1 行（正对照，免得 K4 只是「什么都报红」）"
              % ("✔" if stayed else "✘",))
        if not (grew and stayed):
            sys.exit(1)
    finally:
        db.q("DROP DATABASE IF EXISTS `%s`" % SCRATCH)

    # K6：真树跑一遍（这才是这本判据的正事）
    red, summary = run_real(db, whitelist, probes)
    print("%s K6 真树：%s" % ("✔" if not red else "✘", summary))
    for r in red[:8]:
        print("      " + r)

    # K7：连不上库时必须**拒判**（rc=2）—— 没有库就没有读数，"没数到漂移"不等于绿
    env = dict(os.environ)
    env["MIGCHECK_MYSQL"] = os.path.join(ROOT, "tmp", "no-such-mysql.exe")
    p = subprocess.run([sys.executable, os.path.abspath(__file__)], capture_output=True, env=env, cwd=ROOT)
    ok7 = p.returncode == 2
    print("%s K7 找不到 mysql 客户端 ⇒ 拒判 rc=2（实得 %d）" % ("✔" if ok7 else "✘", p.returncode))
    sys.exit(1 if (red or not ok7) else 0)


def main():
    if "--self-test" in sys.argv:
        self_test()
    whitelist, probes = extract()
    mysql = find_mysql()
    if not mysql or not os.path.exists(mysql):
        die("找不到 mysql 客户端；也不许因此报绿（这本判据只在本机跑，CI 里没有库）")
    user, pwd = read_creds()
    db = Db(mysql, user, pwd)
    red, summary = run_real(db, whitelist, probes)
    print("[扫描] %s" % summary)
    if red:
        for r in red:
            print("✘ %s" % r)
        print("⇒ apply 之前必须先修掉这些：白名单是要被重跑第二遍的")
        sys.exit(1)
    print("✅ 白名单跑两遍零漂移、探针全对：这一版可以交给 guarded 的 apply")


if __name__ == "__main__":
    main()
