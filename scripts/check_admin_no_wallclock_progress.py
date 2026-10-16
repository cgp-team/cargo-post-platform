#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""墙钟定时器不许替后端报进度（队列 #66 / 台账 D-132）。

要防的那件事：调度页原来有一条按 900ms 自增的进度条，配五个写死的阶段名
（分析订单与约束 / 检查车辆运力 / 选择调度站点 / 运行 HACO-CPS / 生成调度方案）。
它走的是自己的表，**算法卡住、报错、甚至请求根本没发出去，它照样走完**。
界面报的是"算法跑到哪一步"，实际报的是"过了多久" —— 这是假仪表，不是装饰。
同一族缺陷在 App 侧也修过一次（§24 假步进 6→0，靠删掉那台假机器）。

口径（只判"定时器实参体内给进度类 ref 赋值"这一形）：
· 扫 `setInterval(` / `window.setInterval(` 的**实参文本**（到与之配对的那个 `)` 为止）。
· 体内出现 `<名字含 progress|percent|stage 的标识符>.value =`（或 `+=`）⇒ 计一处候选。
  名字里没有这一族词的赋值不算（轮询 `refreshData()`、时钟 `tickClock()` 都是合法定时器）。
· 判定范围只在实参体内：**写在命名函数里再由定时器调起**的那一形本闸看不见
  （登记为口径边界，不冒充全覆盖）。
· 候选数为 0 ⇒ 退出码 2："一处都没数到"不能读成"没有假进度"（可能是提取器坏了）。

在册例外（只许按文件计数，降了必须同轮改这张表并写原因）：
· `transport/dispatch/DispatchVisualDialog.vue` 那一处是**路线播放器**：它沿后端已经算好的
  折线推进自己的播放位置（2%/120ms），说的是"播到哪儿了"，不是"后端跑到哪儿了"。
  这一形合法，但它和假进度条**长得一模一样**，所以必须由这张表说出理由，
  而不是靠"看到 setInterval 改 progress 就放行"。

用法：python3 scripts/check_admin_no_wallclock_progress.py [--self-test] [扫描根]
退出码：0 = 候选全部在册；1 = 出现新文件或计数涨跌（含"偷偷修好又不改表"）；
        2 = 扫描根不存在 / 候选数为 0 / 自检里任一 knife 没咬住。
"""
import io
import os
import re
import sys

DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views")

# ===== 在册例外（见文件头部的理由；改这张表必须连原因一起改）=====
# 键一律用正斜杠（scan() 的相对路径也统一成 "/"），否则 Windows 上 os.path.join 的反斜杠
# 会让表与读数对不上 ⇒ 每次部署都红成「出现未登记」。
LEDGER = {
    "transport/dispatch/DispatchVisualDialog.vue": 1,
}
LEDGER_TOTAL = 1

TIMER_CALL = re.compile(r"\bsetInterval\s*\(")
# 先按"名字.value ="这一形取到标识符本身，再用 PROGRESS_NAME 判名字。
# ⚠ 不要把两件事写进一条正则：`\w*(?:[Pp]rogress...)\w*` 这种形状**匹配不了 `progress` 本身**
#   （前导 `[A-Za-z_]\w*` 会把 p 吃掉，后面再要求 progress ⇒ 裸名反而漏判），
#   而播放器那一形用的正是裸名 `progress` —— 自检里它就漏了，靠同目录另一条 fixture 侥幸绿。
IDENT_ASSIGN = re.compile(r"\b([A-Za-z_][\w$]*)\s*\.\s*value\s*(?:=(?![=>])|\+=)")
PROGRESS_NAME = re.compile(r"progress|percent|stage", re.I)


def _match_paren(text, open_idx):
    """返回与 text[open_idx]=='(' 配对的 ')' 下标；跳过引号/模板串里的括号。找不到给 -1。"""
    depth = 0
    quote = None
    i = open_idx
    while i < len(text):
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        else:
            if ch in "'\"`":
                quote = ch
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    return -1


def _code_mask(text):
    """返回与 text 等长的布尔表：True = 该下标是"真代码"（不在字符串/模板串/注释里）。

    为什么要这一层：`setInterval` 出现在字符串或注释里时，它后面的括号**照样能被正则数到**，
    于是"文档里举了个反例"会变成"页面里有个假进度条"。自检 K4 就是钉这一条的。
    （模板串里的 `${…}` 当真代码这一形没做：那是 Vue 模板字符串的少数字状，登记为口径边界。）
    """
    mask = [True] * len(text)
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                mask[k] = False
            i = j
        elif ch == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                mask[k] = False
            i = j
        elif ch in "'\"`":
            quote = ch
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                j += 1
            for k in range(i, j):
                mask[k] = False
            i = j
        else:
            i += 1
    return mask


def scan(root):
    """{相对路径: [(行号, 变量名), ...]}；只数 setInterval 实参体内的进度类赋值。"""
    hits = {}
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".vue"):
                continue
            path = os.path.join(dirpath, name)
            with io.open(path, "r", encoding="utf-8", newline="") as f:
                text = f.read().replace("\r\n", "\n")
            rel = os.path.relpath(path, root).replace("\\", "/")
            mask = _code_mask(text)
            found = []
            for m in TIMER_CALL.finditer(text):
                if not mask[m.start()]:
                    continue
                op = text.index("(", m.start())
                if not mask[op]:
                    continue
                cl = _match_paren(text, op)
                if cl < 0:
                    continue
                body = text[op + 1 : cl]
                body_mask = mask[op + 1 : cl]
                for pm in IDENT_ASSIGN.finditer(body):
                    if not body_mask[pm.start()]:
                        continue
                    if not PROGRESS_NAME.search(pm.group(1)):
                        continue
                    line = text.count("\n", 0, op + 1 + pm.start()) + 1
                    found.append((line, pm.group(1)))
            if found:
                hits[rel] = found
    return hits


def main(argv):
    self_test = "--self-test" in argv
    args = [a for a in argv if a != "--self-test"]
    root = args[0] if args else DEFAULT_ROOT
    if self_test:
        return run_self_test()
    if not os.path.isdir(root):
        print("✘ 扫描根不存在：%s" % root)
        return 2
    hits = scan(root)
    total = sum(len(v) for v in hits.values())
    if total == 0:
        print("✘ 候选数为 0：这一格不能读成「没有假进度」（提取器或扫描根变了）")
        return 2
    print(
        "[扫描] 定时器内给进度类 ref 赋值的候选 %d 处 / %d 个文件（在册例外 %d 处）"
        % (total, len(hits), LEDGER_TOTAL)
    )
    got = {rel: len(v) for rel, v in hits.items()}
    for rel in sorted(got):
        for line, var in hits[rel]:
            print("  %s%s:%d —— %s.value 被定时器自推" % ("（在册）" if rel in LEDGER else "（新增）", rel, line, var))
    new = sorted(set(got) - set(LEDGER))
    if new:
        print("✘ 出现未登记的定时器推进度：%s ⇒ 要么改判据要么按原因入表（不许静默放行）" % "、".join(new))
        return 1
    up = sorted(f for f in got if got[f] > LEDGER.get(f, 0))
    if up:
        print("✘ 计数上涨：%s" % "、".join("%s %d>%d" % (f, got[f], LEDGER[f]) for f in up))
        return 1
    down = sorted(f for f in LEDGER if got.get(f, 0) < LEDGER[f])
    if down:
        print("✘ 计数下降却没改表：%s ⇒ 修好了就同轮把 LEDGER 改小并写原因" % "、".join(down))
        return 1
    if sum(got.values()) != LEDGER_TOTAL:
        print("✘ 总数 %d 与在册 %d 不符" % (sum(got.values()), LEDGER_TOTAL))
        return 1
    print("✅ 定时器推进度全部在册（%d 处，逐条有理由；新增即红）" % LEDGER_TOTAL)
    return 0


FIXTURE_FAKE = """<template><div /></template>
<script setup lang="ts">
const autoStages = ['分析订单与约束', '检查车辆运力', '选择调度站点', '运行 HACO-CPS', '生成调度方案']
const autoProgress = ref(0)
const run = () => {
  const timer = window.setInterval(() => {
    if (autoProgress.value < autoStages.length - 1) autoProgress.value += 1
  }, 900)
}
</script>
"""
FIXTURE_POLL = """<template><div /></template>
<script setup lang="ts">
const rows = ref([])
const timer = window.setInterval(() => refreshData(true), 30000)
</script>
"""
FIXTURE_QUOTED = """<template><div /></template>
<script setup lang="ts">
const s = 'window.setInterval(() => { progress.value = 1 }, 1)'
// window.setInterval(() => { percent.value += 1 }, 900)  ← 文档里举的反例
/* window.setInterval(() => { stage.value = 2 }, 10) */
</script>
"""
FIXTURE_PLAY = """<template><div /></template>
<script setup lang="ts">
const progress = ref(0)
const playTimer = window.setInterval(() => {
  progress.value = Math.min(100, progress.value + 2)
}, 120)
</script>
"""


def run_self_test():
    """knife 必须咬住真字节：逐条断言命中数，而不是"没抛异常就算过"。

    ⚠ 每条用例各占一个目录：早期版本把三条 fixture 写进同一个 `sub/`，
      于是 K2「纯轮询不被误报」数到的是隔壁 fake.vue 的那一处 —— 期望 0 实得 1 报红，
      而 K3 期望 1 实得 1 却是**侥幸绿**（真命中的是 fake，不是 play）。
      这正是"判据数到东西 ≠ 数到那一处"，隔离目录之后每条只可能咬自己。
    """
    import shutil
    import tempfile

    tmp = tempfile.mkdtemp(prefix="wcprog-")
    rc = 0
    try:
        cases = [
            ("K1 假进度条（本轮从调度页删掉的那一块）被数到", "k1", "fake.vue", FIXTURE_FAKE, 1),
            ("K2 纯轮询不被误报", "k2", "poll.vue", FIXTURE_POLL, 0),
            ("K3 播放器那一形被数到（靠表说理由，不靠放行）", "k3", "play.vue", FIXTURE_PLAY, 1),
            ("K4 引号里的 setInterval 不算", "k4", "q.vue", FIXTURE_QUOTED, 0),
        ]
        for name, subdir, fname, body, want in cases:
            d = os.path.join(tmp, subdir)
            os.makedirs(d, exist_ok=True)
            p = os.path.join(d, fname)
            with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            got = sum(len(v) for v in scan(d).values())
            if got != want:
                print("✘ %s：期望 %d 处，实得 %d" % (name, want, got))
                rc = 1
            else:
                print("✔ %s（%d 处）" % (name, got))
        # K5：把本轮**真删掉的那一块**塞回真文件（内存副本），闸必须咬住
        #     —— 光有 fixture 只能证明正则会认这个形状，证明不了它对现在这棵树还有牙齿。
        real = os.path.join(DEFAULT_ROOT, "transport", "dispatch", "index.vue")
        if not os.path.isfile(real):
            print("! K5 跳过：真文件不存在（%s）" % real)
        else:
            with io.open(real, "r", encoding="utf-8", newline="") as f:
                src = f.read()
            anchor = "const runAutoSmart = async () => {"
            if anchor not in src:
                print("✘ K5 锚点没落：`%s` 不在真文件里 ⇒ 注入等于没注入" % anchor)
                rc = 1
            else:
                injected = src.replace(
                    anchor
                    + "\n",
                    anchor
                    + "\n  const timer = window.setInterval(() => {\n"
                    + "    if (autoProgress.value < autoStages.length - 1) autoProgress.value += 1\n"
                    + "  }, 900)\n",
                    1,
                )
                if injected == src:
                    print("✘ K5 注入后字节没变")
                    rc = 1
                else:
                    d = os.path.join(tmp, "k5")
                    os.makedirs(d, exist_ok=True)
                    with io.open(os.path.join(d, "index.vue"), "w", encoding="utf-8", newline="\n") as f:
                        f.write(injected)
                    got = sum(len(v) for v in scan(d).values())
                    if got != 1:
                        print("✘ K5 假进度条塞回真文件：期望 1 处，实得 %d" % got)
                        rc = 1
                    else:
                        print("✔ K5 假进度条塞回真调度页会被数到（1 处）")
        # K6：真树读数必须与在册表逐字相符（这条把"自检过了"钉在真产物上）
        hits = scan(DEFAULT_ROOT) if os.path.isdir(DEFAULT_ROOT) else None
        if hits is None:
            print("! K6 跳过：不在仓库根运行（扫描根 %s 不存在）" % DEFAULT_ROOT)
        else:
            got = {rel: len(v) for rel, v in hits.items()}
            if got != LEDGER or sum(got.values()) != LEDGER_TOTAL:
                print("✘ K6 真树读数与在册表不符：%s != %s" % (got, LEDGER))
                rc = 1
            else:
                print("✔ K6 真树读数与在册表一致（%s）" % got)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
