#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""敏感字段掩码的唯一出口（队列 #93 / 契约 docs/AI_ASSIST_BOUNDARY.md §4）。

为什么现在就要这本闸：AI 那条线马上要往外部推理服务送业务摘要。到时候再补"别把明文手机号带出去"，
一定会变成"每个调用处各写一份掩码" —— 而各写一份就一定有人漏一种形状（今天加幂等键时我已经撞过同族问题：
在册表里 12 条理由有 4 条是看名字编的）。所以工具先落地、判据先能红，再让她用。

四件事：
· A1 出口在：`SensitiveDataMask` 里必须有 `mobile(` 与 `tail4(`。**数不到 ⇒ rc=2**（判据没跑起来，不许读成"没问题"）。
· A2 唯一实现：transport 模块里除本类外，再出现 `DesensitizedUtil` 或自写 `"****"` 掩码 ⇒ 违规。
· A3 空值不许被掩成"像被掩过"：两个方法各要有一处 `isBlank(raw)` 原样返回（恰好 2 处）。
     —— 这与金额缺值不许念成 `0.00` 是同一条纪律：null 进 null 出，读的人才分得清"没给"和"给了但屏蔽"。
· A4 AI 侧的代码（`yudao-module-ai-assist/**`、后台 `views/transport/ai/**`、`api/transport/ai/**`）
     不许引用明文手机号字段（`getMobile(` / `.mobile` / `mobile:`）。**这些目录还不存在时打一条"跳过"**，
     这本闸不靠它判绿——A1–A3 一直活着，所以"跳过"不等于空转（这一点自检里 K6 单独钉）。

用法：python3 scripts/check_admin_ai_data_mask.py [--self-test] [仓库根]
退出码：0 = 合规；1 = 有违规/AI 侧读到明文；2 = 出口找不到（判据没跑起来）。
"""
import io
import os
import re
import sys

# 输出流强制 UTF-8（GBK 控制台上不这么做，打印中文时抛 UnicodeEncodeError，
# rc=1 会被读成「这一本判红」，而实际是判据一次都没判）
import sys as _sys
for _s in (_sys.stdout, _sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

MASK_REL = os.path.join("yudao-module-transport", "src", "main", "java", "cn", "iocoder", "yudao",
                        "module", "transport", "util", "SensitiveDataMask.java")
MODULE_REL = os.path.join("yudao-module-transport", "src", "main", "java")
AI_ROOTS = [
    "yudao-module-ai-assist",
    os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views", "transport", "ai"),
    os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "api", "transport", "ai"),
]
MASK_API = re.compile(r"public\s+static\s+String\s+(mobile|tail4)\s*\(")
BLANK_GUARD = re.compile(r"isBlank\(\s*raw\s*\)")
HOME_GROWN = re.compile(r"DesensitizedUtil|\"\*{4}\"")
PLAIN_MOBILE = re.compile(r"getMobile\s*\(|\.mobile\b|mobile\s*:")
SRC_EXT = (".java", ".ts", ".vue")
PIN_BLANK_GUARDS = 2


def _read(p):
    with io.open(p, "r", encoding="utf-8", errors="replace", newline="") as f:
        return f.read().replace("\r\n", "\n")


def _walk(root, exts=SRC_EXT):
    for dirpath, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ("node_modules", "target", "build", "__tests__")]
        for n in sorted(names):
            if n.endswith(exts):
                yield os.path.join(dirpath, n)


def check(root):
    """返回 (bad, counters)：bad 是逐条违规文本，counters 是打出来的对照读数。"""
    bad = []
    mp = os.path.join(root, MASK_REL)
    if not os.path.isfile(mp):
        return ["✘ 掩码出口不存在：%s（判据没跑起来，不许读成「都脱敏了」）" % MASK_REL.replace(os.sep, "/")], None
    mask_src = _read(mp)
    apis = set(MASK_API.findall(mask_src))
    counters = {"出口方法": sorted(apis), " java 文件": 0, "自写掩码": 0, "空值守卫": len(BLANK_GUARD.findall(mask_src))}
    for need in ("mobile", "tail4"):
        if need not in apis:
            bad.append("✘ 出口缺方法 %s()：调用方会绕过去自己掩，第二份一定漏形状" % need)
    if counters["空值守卫"] != PIN_BLANK_GUARDS:
        bad.append("✘ 空值守卫现读 %d 处（要恰好 %d 处）：把缺值掩成占位串，读的人分不清「没给」和「已屏蔽」"
                   % (counters["空值守卫"], PIN_BLANK_GUARDS))

    mod = os.path.join(root, MODULE_REL)
    scanned = 0
    for p in _walk(mod, (".java",)):
        scanned += 1
        rel = os.path.relpath(p, root).replace(os.sep, "/")
        if rel == MASK_REL.replace(os.sep, "/"):
            continue
        if HOME_GROWN.search(_read(p)):
            counters["自写掩码"] += 1
            bad.append("✘ 第二处掩码实现：%s —— 收敛到唯一出口，否则两边会各自漂移" % rel)
    counters[" java 文件"] = scanned
    if scanned == 0:
        bad.append("✘ transport 模块一个 .java 都没扫到 ⇒ 扫描面变了，A2 的 0 不可信")

    ai_present = [r for r in AI_ROOTS if os.path.isdir(os.path.join(root, r))]
    if not ai_present:
        counters["AI 面"] = "跳过（目录尚未落地）"
    else:
        hits = 0
        for r in ai_present:
            for p in _walk(os.path.join(root, r)):
                for i, ln in enumerate(_read(p).split("\n"), 1):
                    if PLAIN_MOBILE.search(ln):
                        hits += 1
                        bad.append("✘ AI 侧读到明文手机号字段：%s:%d —— 只能过 SensitiveDataMask 或收聚合摘要 DTO"
                                   % (os.path.relpath(p, root).replace(os.sep, "/"), i))
        counters["AI 面"] = "%d 个目录，命中 %d 处" % (len(ai_present), hits)
    return bad, counters


def main(argv):
    if "--self-test" in argv:
        return run_self_test()
    root = argv[0] if argv else "."
    if not os.path.isdir(root):
        print("✘ 仓库根不存在：%s" % root)
        return 2
    bad, counters = check(root)
    if counters is None:
        print(bad[0])
        return 2
    print("[扫描] 出口方法 %s；transport .java %d 个；自写掩码 %d 处；空值守卫 %d 处；AI 面 %s"
          % ("/".join(counters["出口方法"]) or "无", counters[" java 文件"], counters["自写掩码"],
             counters["空值守卫"], counters["AI 面"]))
    for b in bad:
        print(b)
    if bad:
        return 1
    print("✅ 掩码只有一处实现、缺值不被折叠成占位串、AI 侧引用不到明文手机号")
    return 0


# ---------------- 自检：一条刀一个目录 ----------------

MASK_OK = '''package p;
import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;
public final class SensitiveDataMask {
    public static String mobile(String raw) {
        if (StrUtil.isBlank(raw)) { return raw; }
        return DesensitizedUtil.mobilePhone(raw.trim());
    }
    public static String tail4(String raw) {
        if (StrUtil.isBlank(raw)) { return raw; }
        return "****" + raw.trim().substring(raw.length() - 4);
    }
}
'''
MASK_NO_BLANK = MASK_OK.replace('        if (StrUtil.isBlank(raw)) { return raw; }\n        return DesensitizedUtil',
                                '        return DesensitizedUtil')
SOME_OTHER = '''package p;
import cn.hutool.core.util.DesensitizedUtil;
public class Other {
    String m = DesensitizedUtil.mobilePhone(in);
}
'''
AI_LEAK = '''package ai;
public class Foo {
    String phone = vo.getMobile();
}
'''


def _mk(tmp, name, mask_body, extra=None, ai=None):
    d = os.path.join(tmp, name)
    mp = os.path.join(d, MASK_REL)
    os.makedirs(os.path.dirname(mp), exist_ok=True)
    with io.open(mp, "w", encoding="utf-8", newline="\n") as f:
        f.write(mask_body)
    for rel, body in (extra or []):
        p = os.path.join(d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(body)
    for rel, body in (ai or []):
        p = os.path.join(d, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(body)
    return d


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix="mask-")
    other_java = os.path.join(MODULE_REL, "p", "Other.java")
    ai_java = os.path.join("yudao-module-ai-assist", "src", "main", "java", "ai", "Foo.java")
    cases = [
        ("K1 合规形状 ⇒ 全绿", dict(mask=MASK_OK, want=0)),
        ("K2 第二处自写掩码必须红", dict(mask=MASK_OK, extra=[(other_java, SOME_OTHER)], want=1)),
        ("K3 出口缺空值守卫必须红（缺值会被掩成占位串）", dict(mask=MASK_NO_BLANK, want=1)),
        ("K4 AI 侧引用明文手机号必须红", dict(mask=MASK_OK, ai=[(ai_java, AI_LEAK)], want=1)),
    ]
    try:
        for i, (name, spec) in enumerate(cases):
            d = _mk(tmp, "k%d" % (i + 1), spec["mask"], spec.get("extra"), spec.get("ai"))
            bad, counters = check(d)
            got = 0 if not bad else 1
            if counters is None or got != spec["want"]:
                print("✘ %s：rc 期望 %d，实得 %s（%s）" % (name, spec["want"], counters and got, bad[:1]))
                rc = 1
            else:
                print("✔ %s" % name)
        # K5 出口文件不存在 ⇒ rc=2（不许读成"都脱敏了"）
        d5 = os.path.join(tmp, "k5")
        os.makedirs(os.path.join(d5, MODULE_REL), exist_ok=True)
        bad5, c5 = check(d5)
        if not (bad5 and c5 is None):
            print("✘ K5 出口缺失时没走 fail-closed（bad=%s counters=%s）" % (bad5[:1], c5))
            rc = 1
        else:
            print("✔ K5 出口不存在 ⇒ 判据拒判（rc=2 那条路）")
        # K6 AI 目录不存在 ⇒ 记"跳过"而整体仍绿（这本闸不靠 AI 面判绿）
        d6 = _mk(tmp, "k6", MASK_OK)
        bad6, c6 = check(d6)
        if bad6 or not c6 or "跳过" not in c6["AI 面"]:
            print("✘ K6 AI 面缺席时应打「跳过」且不判违规（%s / %s）" % (bad6[:1], c6 and c6["AI 面"]))
            rc = 1
        else:
            print("✔ K6 AI 面未落地时打跳过，A1–A3 仍活着（不是空转）")
        # K7 真树
        if os.path.isfile(os.path.join(".", MASK_REL)):
            bad7, _c7 = check(".")
            if bad7:
                print("✘ K7 真树 %d 条：%s" % (len(bad7), bad7[:2]))
                rc = 1
            else:
                print("✔ K7 真树合规")
        else:
            print("! K7 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
