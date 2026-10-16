#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读接口失败之后，页面必须留下"这次没读到"那一格（队列 #65 / 台账 D-130）。

要防的那件事有两句同样的话术在掩护它：
· **数据没清** ⇒ 屏幕继续画上一次的结果：换个单号查失败、页面还在念上一个订单的运输链；
  点了 B 单失败、详情表还是 A 单那几段。
· **只弹一次 toast** ⇒ toast 掉了页面只剩空白，而空白旁边写着"暂无事件 / 点击左侧订单池中的订单查看"，
  这把"我没取到"说成了"这单没有"或"你还没点"。

口径（只判"读数据的那类 try/catch"，写操作的 catch 不在这一格）：
· try 体里出现过 `await XxxApi.get…/page…/list…/simple…`（读口）才算候选；注释行不参与判定。
· 对应的 catch 体必须做到两件事**至少一件**：
  ① 写错误态：给某个名字以 `Error` / `error` / `failed` 结尾的 ref 赋值；
  ② 清掉数据：把某个 `.value` 赋成 `null` 或 `[]`。
  两条都没有 ⇒ 判"失败会被读成空态/旧数据"。
· 候选数为 0 ⇒ 退出码 2：**"一处都没数到"不能读成"失败都有出口"**。

用法：python3 scripts/check_admin_catch_leaves_data.py [扫描根]
退出码：0 = 未达标处**全部在册**（见文件顶部 KNOWN_UNFIXED）；
        1 = 出现新文件、某文件计数上涨、或有文件降了却没改在册表（"修好了不登记"同样红）；
        2 = 扫描根不存在 / 候选数为 0。
"""
import io
import os
import re
import sys

DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views", "transport")
# ===== 在册欠账（本轮实测钉死，只许降不许升；降了必须同轮改这张表）=====
# 为什么不当场全修：这 25 处里有 19 个文件是"列表页 getList 只有 try/finally、没有 catch"，
# 那一族与队列 #68（三态并格）是同一块活，摊开改会把这个收口撑成另一轮。
# 但**不能因为修不完就不立闸**：立成在册表之后，任何新增、任何上涨、以及"偷偷修好又不登记"都会红。
# ⚠ 表由 24 → **空**（2026-10-16，队列 #68 批次 1+2 / 台账 D-134）：这是**进展**，不是口径修正 ——
#   13 个列表页与 6 处 dispatch 读口、5 处表单/详情读口全部补了"清数据 + 留一条看得见的字"。
#   表清空之后这本闸仍然有用：候选数（现读 34 处）照样数，任何一页把出口删掉都会以"新出现的未达标文件"红回来。
KNOWN_UNFIXED = {}
KNOWN_TOTAL = 0
# ⚠ 表上从 25 降到 24 不是"又修好了一格"：`dispatch/DispatchVisualDialog.vue` 那条是**判据的误报**
#   —— 它的 catch 写的是 `legRoadFailed.value` / `nextFailed`，而 ERR_STATE 起初只认结尾 `Error|error|failed`
#   （小写 f），没认大写 `Failed`。放宽正则后它自然出局。这种"数字变小"必须连原因一起改表，
#   不然下一轮会把一次口径修正读成一次进展。
READ_CALL = re.compile(r"\bawait\s+\w*Api\.(get|page|list|simple|export)\w*\(", re.I)
ERR_STATE = re.compile(r"\b\w*(?:Error|error|[Ff]ailed)\.value\s*=")
CLEAR = re.compile(r"\b\w+\.value\s*=\s*(?:null|\[\])\s*$|\b\w+\.value\s*=\s*(?:null|\[\])\s*[;,]", re.M)


def read(path):
    return io.open(path, encoding="utf-8", errors="replace").read().replace("\r\n", "\n")


def strip_comments(body):
    keep = []
    for line in body.split("\n"):
        s = line.strip()
        if s.startswith("//") or s.startswith("*") or s.startswith("/*"):
            continue
        keep.append(re.sub(r"//.*$", "", line))
    return "\n".join(keep)


def match_brace(text, open_idx):
    depth, j = 0, open_idx
    while j < len(text):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    return -1


def pairs(text):
    """(try 行号, try 体, catch 体或 None)。"""
    out = []
    for m in re.finditer(r"\btry\s*\{", text):
        ti = text.index("{", m.start())
        te = match_brace(text, ti)
        if te < 0:
            continue
        line_no = text.count("\n", 0, m.start()) + 1
        rest = text[te + 1:]
        c = re.match(r"\s*catch\s*(?:\([^)]*\))?\s*\{", rest)
        body = None
        if c:
            ci = te + 1 + rest.index("{", c.start())
            ce = match_brace(text, ci)
            if ce > 0:
                body = text[ci + 1:ce]
        out.append((line_no, text[ti + 1:te], body))
    return out


def scan(path):
    text = read(path)
    cands, bad = 0, []
    for line_no, try_body, catch_body in pairs(text):
        if not READ_CALL.search(strip_comments(try_body)):
            continue
        cands += 1
        if catch_body is None:
            bad.append((line_no, "这个读接口根本没有 catch（失败会抛出事件处理函数，页面留旧数据）"))
            continue
        cb = strip_comments(catch_body)
        if ERR_STATE.search(cb) or CLEAR.search(cb):
            continue
        bad.append((line_no, " ".join(cb.split())[:70] or "（catch 体是空的）"))
    return cands, bad


def main():
    args = [a for a in sys.argv[1:] if a != "--report-only"]
    report_only = "--report-only" in sys.argv[1:]
    root = args[0] if args else DEFAULT_ROOT
    if not os.path.isdir(root):
        sys.stderr.write("扫描根不存在：%s\n" % root)
        return 2
    total, hits = 0, []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d != "__tests__"]
        for fn in sorted(filenames):
            if not fn.endswith(".vue"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            n, bad = scan(path)
            total += n
            for ln, why in bad:
                hits.append((rel, ln, why))
    print("[扫描] %s ⇒ 读接口的 try/catch 候选 %d 处" % (root, total))
    if total == 0:
        sys.stderr.write("一处读接口的候选都没数到 —— 判据没跑起来，不许读成“失败都有出口”\n")
        return 2
    got = {}
    for rel, ln, why in hits:
        got[rel] = got.get(rel, 0) + 1
    for rel, ln, why in hits:
        if KNOWN_UNFIXED.get(rel, 0) >= got[rel] and rel in KNOWN_UNFIXED:
            print("  （在册欠账）%s:%d —— %s" % (rel, ln, why))
        else:
            print("× %s:%d  —— %s" % (rel, ln, why))
    new_files = [f for f in got if f not in KNOWN_UNFIXED]
    grew = [(f, got[f], KNOWN_UNFIXED[f]) for f in got if f in KNOWN_UNFIXED and got[f] > KNOWN_UNFIXED[f]]
    shrank = [(f, got.get(f, 0), KNOWN_UNFIXED[f]) for f in KNOWN_UNFIXED if got.get(f, 0) < KNOWN_UNFIXED[f]]
    # ⚠ 这行原来写的是 `sum(a - b for a, b in grew)` —— grew 的每一项是 (文件, 现读, 在册) 三元组，
    #   两条解包必然 ValueError。它一直"绿"是因为这条分支从来没被执行过（没有文件涨过），
    #   #67 把 notification 那格的读法一改才第一次走到这里 ⇒ 崩在打印之后、判定之前，
    #   退出码 1 看着像"判红"，其实判据根本没判完。
    fresh = len(new_files) + sum(a - b for _f, a, b in grew)
    if new_files:
        print("❌ 新出现的未达标文件（不在册，必须先修或明确登记）：%s" % "、".join(sorted(new_files)))
    for f, a, b in grew:
        print("❌ %s 从在册 %d 处涨到 %d 处" % (f, b, a))
    for f, a, b in shrank:
        print("❌ %s 从在册 %d 处降到 %d 处 —— 修好了就把本文件顶部的在册表改小（同一轮），"
              "不许让它停在 0 而没人知道这一格已经过了" % (f, b, a))
    if fresh:
        print("❌ 未达标 %d 处不在册 / 在册总数现读 %d（在册 %d）"
              % (fresh, sum(got.values()), KNOWN_TOTAL))
        return 1
    if shrank:
        return 1
    if sum(got.values()) != KNOWN_TOTAL:
        print("❌ 现读总数 %d ≠ 在册 %d（有文件的计数互相顶掉了，逐条核）"
              % (sum(got.values()), KNOWN_TOTAL))
        return 1
    print("✅ 未达标全部在册（%d 处 / %d 个文件，另有 %d 处读接口失败已留可见出口）"
          % (KNOWN_TOTAL, len(KNOWN_UNFIXED), total - KNOWN_TOTAL))
    return 0


if __name__ == "__main__":
    sys.exit(main())
