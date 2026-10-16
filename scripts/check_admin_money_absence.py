#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""金额/里程缺值不许折叠成"看起来像结论"的数（队列 #68 / 台账 D-134）。

坏法有两句同样的话术在掩护：
· `Number(value ?? 0).toFixed(2)` ⇒ 缺值念成 `0.00 元`。0 是一个业务结论（今天真的没进账），
  而界面把它当成"后端说没有"的默认外观 —— 运营读的是"今天 0 元"，真实状态是"这一格没取到"。
· `¥{{ row.price }}` ⇒ 缺值念成光一个 `¥`（或 `¥undefined`），既不是数也不是"没给"。

口径：
· 折叠命中 = ①`¥{{ … }}` 直接把值串进模板；②金额/里程类表达式后跟 `?? 0` / `|| 0` 再 `.toFixed(`。
· **注释里的反例不算**：本轮自己在 KDoc 里写了"原来那是 `Number(value ?? 0).toFixed(2)`"，
  一条只认形状的正则会把它判成违规（§八 那一族"判据模式命中自己"）⇒ 先剥注释再匹配。
· 对照数 = `moneyText(` / `distanceText(` 的命中。**对照数为 0 ⇒ rc=2**：
  这说明扫描没看见任何合规渲染，多半是提取器或目录变了，不能读成"全都干净"。

用法：python3 scripts/check_admin_money_absence.py [--self-test] [扫描根]
退出码：0 = 折叠命中 0 且对照数 >0；1 = 还有折叠命中；2 = 目录不存在 / 对照数为 0。
"""
import io
import os
import re
import sys

DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views", "transport")

MONEY_NAME = r"(?:amount|price|fee|revenue|cost|totalDistance|distance|totalAmount|balance|money)"
TOFIXED_RE = re.compile(r"\.toFixed\s*\(")
FOLD_RE = re.compile(r"(?:\?\?|\|\|)\s*0\b")
MONEY_HINT = re.compile(MONEY_NAME, re.I)
RAW_YEN_RE = re.compile(r"¥\s*\{\{[^}]*\}\}")
GOOD_RE = re.compile(r"\b(?:moneyText|distanceText)\s*\(")


def strip_comments(text):
    """剥掉 HTML 注释 / 块注释 / 行注释。
    ⚠ 块注释与 HTML 注释按行数补回换行：直接换成空格会让后面所有命中报出**错行的行号**，
      而错行号的报告没人会去核（这一族在 §八 叫"读数指向不存在的位置"）。"""
    text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    text = re.sub(r"(?<![:/\w])//[^\n]*", "", text)
    return text


def scan(root):
    bad, good, files = [], 0, 0
    for dirpath, _d, names in os.walk(root):
        for name in sorted(names):
            if not name.endswith(".vue"):
                continue
            files += 1
            p = os.path.join(dirpath, name)
            with io.open(p, "r", encoding="utf-8", newline="") as f:
                text = f.read().replace("\r\n", "\n")
            good += len(GOOD_RE.findall(text))
            body = strip_comments(text)
            for m in RAW_YEN_RE.finditer(body):
                line = body.count("\n", 0, m.start()) + 1
                bad.append((p, line, "裸 ¥ 插值", m.group(0)[:70]))
            # 折叠形的三件事凑齐才算：同一条语句里 ①.toFixed( ②?? 0 / || 0 ③点名金额/里程类字段
            # （只看 `?? 0` 会误伤 `(rows ?? []).length`、`idx || 0` 这些不是钱的写法，误伤一次就有人把整本闸调松）
            for m in TOFIXED_RE.finditer(body):
                st = body.rfind("\n", 0, m.start()) + 1
                en = body.find("\n", m.end())
                en = len(body) if en < 0 else en
                stmt = body[st:en]
                if not FOLD_RE.search(stmt) or not MONEY_HINT.search(stmt):
                    continue
                line = body.count("\n", 0, m.start()) + 1
                bad.append((p, line, "缺值折叠成 0", stmt.strip()[:78]))
    return bad, good, files


def main(argv):
    self_test = "--self-test" in argv
    args = [a for a in argv if a != "--self-test"]
    root = args[0] if args else DEFAULT_ROOT
    if self_test:
        return run_self_test()
    if not os.path.isdir(root):
        print("✘ 扫描根不存在：%s" % root)
        return 2
    bad, good, files = scan(root)
    if good == 0:
        print("✘ %d 个 .vue 里一个 moneyText/distanceText 都没数到 ⇒ 判据看不见合规渲染，不许报绿" % files)
        return 2
    print("[扫描] %d 个 .vue；合规渲染 %d 处；折叠/裸串 %d 处" % (files, good, len(bad)))
    for p, line, why, snippet in bad:
        print("  ✘ %s:%d —— %s：%s" % (p.replace("\\", "/"), line, why, snippet))
    if bad:
        return 1
    print("✅ 金额与里程都从 moneyText/distanceText 走：缺值念「没给」，真零念 0.00")
    return 0


FX_FOLD = '<template><div>{{ Number(scope.row.amount ?? 0).toFixed(2) }}</div></template>\n'
FX_YEN = '<template><div>¥{{ scope.row.price }}</div></template>\n'
FX_GOOD = """<template><div>{{ moneyText(scope.row.price) }} / {{ distanceText(row.totalDistance) }}</div></template>
<script setup lang="ts">
import { moneyText } from '../constants'
</script>
"""
FX_COMMENT = """<template>
  <!-- 这里以前写的是 ¥{{ row.price }}，现在走 moneyText -->
  <div>{{ moneyText(row.price) }}</div>
</template>
<script setup lang="ts">
// 原来那形是 Number(value ?? 0).toFixed(2)，缺值会被念成 0.00
const fmt = (v: number) => v.toFixed(2)
</script>
"""


FX_NOT_MONEY = ('<template><div>{{ (rows ?? []).length }}</div></template>\n'
                '<script setup lang="ts">\nconst n = idx || 0\nconst pct = (n / total).toFixed(2)\n</script>\n')


def run_self_test():
    """每条刀各占一个目录：上一轮同目录放多条 fixture，读数互相顶包（K3 侥幸绿）。"""
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix="moneyabs-")
    try:
        cases = [
            ("K1 折叠形（金额字段 + ?? 0 + toFixed）被数到", FX_FOLD, 1, False),
            ("K2 裸 ¥ 插值被数到", FX_YEN, 1, False),
            ("K3 合规渲染不算折叠", FX_GOOD, 0, True),
            ("K4 注释里的反例不算（HTML 注释 + 行注释各一条）", FX_COMMENT, 0, True),
            ("K5 非金额的 || 0 与 toFixed 不误伤", FX_NOT_MONEY, 0, False),
        ]
        for i, (name, body, want, need_good) in enumerate(cases):
            d = os.path.join(tmp, "k%d" % (i + 1))
            os.makedirs(d, exist_ok=True)
            with io.open(os.path.join(d, "a.vue"), "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            bad, good, _files = scan(d)
            if len(bad) != want:
                print("✘ %s：期望 %d 处，实得 %d（%s）" % (name, want, len(bad), [b[3] for b in bad]))
                rc = 1
            elif need_good and good == 0:
                print("✘ %s：剥注释把合规渲染也剥没了（good=%d）" % (name, good))
                rc = 1
            else:
                print("✔ %s（命中 %d / 对照 %d）" % (name, len(bad), good))
        # K6 一个合规渲染都没有 ⇒ 必须走 rc=2 那条路（不能报绿）
        d6 = os.path.join(tmp, "k6")
        os.makedirs(d6, exist_ok=True)
        with io.open(os.path.join(d6, "a.vue"), "w", encoding="utf-8", newline="\n") as f:
            f.write("<template><div>没有金额</div></template>\n")
        _b6, good6, _f6 = scan(d6)
        if good6 != 0:
            print("✘ K6 空样本里对照数不是 0（=%d）⇒ 这条反证没意义" % good6)
            rc = 1
        else:
            print("✔ K6 对照数为 0 的样本能被识别（main 会判 rc=2，不读成绿）")
        # K7 真树
        if os.path.isdir(DEFAULT_ROOT):
            bad7, good7, files7 = scan(DEFAULT_ROOT)
            if bad7:
                print("✘ K7 真树还有 %d 处折叠/裸串：%s" % (len(bad7), [(b[0].split("/")[-1], b[1]) for b in bad7[:6]]))
                rc = 1
            elif good7 == 0:
                print("✘ K7 真树对照数为 0")
                rc = 1
            else:
                print("✔ K7 真树 %d 个 .vue / 合规渲染 %d 处 / 折叠 0 处" % (files7, good7))
        else:
            print("! K7 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
