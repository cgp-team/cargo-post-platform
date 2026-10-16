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
      python3 scripts/check_admin_catch_leaves_data.py --self-test    # 九把刀各占一个目录
退出码：0 = 未达标处**全部在册**（见文件顶部 KNOWN_UNFIXED）；
        1 = 出现新文件、某文件计数上涨、或有文件降了却没改在册表（"修好了不登记"同样红）；
        2 = 扫描根不存在 / 候选数为 0。
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
    argv = sys.argv[1:]
    if "--self-test" in argv:
        return run_self_test()
    args = [a for a in argv if a != "--report-only"]
    report_only = "--report-only" in argv
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


# ---------------- 自检（九把刀，一条一个目录） ----------------
# 这本判的是"失败之后页面留没留下一格真话"，五种坏法各对一把：没 catch、catch 什么都不做、
# 把写口当读口数、把注释里的读口数成候选，以及最阴的一种——修好了却不改在册表（数字停在旧值没人发现）。

def _vue(body):
    return "<template><div>x</div></template>\n<script setup lang=\"ts\">\n%s</script>\n" % body


FX_NO_CATCH = _vue("""
const load = async () => {
  try {
    rows.value = await OrderApi.getOrderPage(query)
  } finally {
    loading.value = false
  }
}
""")
FX_ERROR_STATE = _vue("""
const load = async () => {
  try {
    rows.value = await OrderApi.getOrderPage(query)
  } catch (e) {
    rows.value = []
    listError.value = '列表没读到：' + e.message
  }
}
""")
FX_CLEAR_ONLY = _vue("""
const load = async () => {
  try {
    detail.value = await OrderApi.getOrder(id)
  } catch (e) {
    detail.value = null
  }
}
""")
FX_COMMENT_ONLY = _vue("""
const load = async () => {
  try {
    // await OrderApi.getOrderPage(query)   这行早已注释掉
    const r = await otherLocalThing()
  } catch (e) {
  }
}
""")
FX_WRITE_CALL = _vue("""
const submit = async () => {
  try {
    await OrderApi.createOrder(form)
  } catch (e) {
    message.error('失败了')
  }
}
""")


def _write(tmp, name, body):
    d = os.path.join(tmp, name)
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "a.vue")
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    return d, p


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix="catchld-")
    cases = [
        ("K1 只有 try/finally、没有 catch 的读口必须被数到", FX_NO_CATCH, 1, 1),
        ("K2 catch 写了错误态就达标", FX_ERROR_STATE, 1, 0),
        ("K3 catch 只清数据也算达标", FX_CLEAR_ONLY, 1, 0),
        ("K4 注释里的读口不算候选（否则判据数的是假形状）", FX_COMMENT_ONLY, 0, 0),
        ("K5 写口的 catch 不归这本管", FX_WRITE_CALL, 0, 0),
    ]
    try:
        for i, (name, body, want_c, want_b) in enumerate(cases):
            _d, p = _write(tmp, "k%d" % (i + 1), body)
            c, b = scan(p)
            if c != want_c or len(b) != want_b:
                print("✘ %s：候选 %d/%d、未达标 %d/%d（%s）"
                      % (name, c, want_c, len(b), want_b, [x[1][:24] for x in b]))
                rc = 1
            else:
                print("✔ %s（候选 %d / 未达标 %d）" % (name, c, len(b)))

        argv_bak = list(sys.argv)
        ledger_bak = dict(KNOWN_UNFIXED)
        total_bak = KNOWN_TOTAL

        def run_main(root_arg, ledger, ledger_total):
            sys.argv = ["x", root_arg]
            KNOWN_UNFIXED.clear()
            KNOWN_UNFIXED.update(ledger)
            globals()["KNOWN_TOTAL"] = ledger_total
            try:
                return main()
            finally:
                sys.argv = list(argv_bak)
                KNOWN_UNFIXED.clear()
                KNOWN_UNFIXED.update(ledger_bak)
                globals()["KNOWN_TOTAL"] = total_bak

        # K6 修好了却不改在册表：表里写着 1 处，树上是 0 处 ⇒ 必须红
        d6, _ = _write(tmp, "k6", FX_ERROR_STATE)
        got6 = run_main(d6, {"a.vue": 1}, 1)
        if got6 != 1:
            print("✘ K6 在册表停在旧值却 rc=%d（期望 1：数字变小必须同轮改表）" % got6)
            rc = 1
        else:
            print("✔ K6「修好了不登记」红")
        # K7 新出现的未达标文件不在册 ⇒ 红
        d7, _ = _write(tmp, "k7", FX_NO_CATCH)
        got7 = run_main(d7, {}, 0)
        if got7 != 1:
            print("✘ K7 新的未达标文件 rc=%d（期望 1）" % got7)
            rc = 1
        else:
            print("✔ K7 新增未达标即红（不在册就不许混过去）")
        # K8 一个候选都没数到 ⇒ rc=2，不许读成"失败都有出口"
        d8 = os.path.join(tmp, "k8")
        os.makedirs(d8, exist_ok=True)
        with io.open(os.path.join(d8, "a.vue"), "w", encoding="utf-8", newline="\n") as f:
            f.write("<template><div>只读展示</div></template>\n")
        got8 = run_main(d8, {}, 0)
        if got8 != 2:
            print("✘ K8 无候选 rc=%d（期望 2）" % got8)
            rc = 1
        else:
            print("✔ K8 候选数为 0 走 rc=2 出口")
        # K9 真树：在册表与读数必须还互相对得上
        if os.path.isdir(DEFAULT_ROOT):
            got9 = run_main(DEFAULT_ROOT, ledger_bak, total_bak)
            if got9 != 0:
                print("✘ K9 真树 rc=%d" % got9)
                rc = 1
            else:
                print("✔ K9 真树在册对齐（见上一行读数）")
        else:
            print("! K9 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())
