#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""不可逆写口必须先让用户看见后果（队列 #67 / 台账 D-133）。

要防的那件事：后台这些按钮点下去**没有撤销入口**，而页面原来直接执行 ——
· 方案审核「通过」：置为已下发 + 事务提交后给每个订单的用户推微信、通知司机（DispatchServiceImpl.reviewPlan）
· 发车核验「通过」：方案转执行中 + 该车那批订单置为已发车（同上 departureCheck）
· 人车「绑定」：后端先把该司机现有的车、该车现有的司机**双双解绑**再 insert（DriverVehicleServiceImpl.bind）
· 通知「发送」：进用户消息中心，撤不回
· 运输段「重调度」：换掉现有人车绑定；找不到备选时把方案置为「异常」并通知管理员（MultiLegServiceImpl.replanLeg）
全局前提（实测）：`controller/admin/**` 下 40 个写接口**没有一个**带 `@Idempotent`，
所以"点错了"这件事在后端不被拦，前端那一句确认是唯一的闸。

口径（判"名字"也判"落点"）：
· 名单里的每个 API 调用点，必须落在一个**顶层函数体**里，且该函数体内出现 `confirm(`
  （`message.confirm(...)` 或 `ElMessageBox.confirm(...)` 都算）。
· 调用点找不到 ⇒ 红："名单里那条已经不成立了"（名单必须跟着现实改，不许悄悄留着当装饰）。
· 找到了但函数体里没有 confirm ⇒ 红，点名文件与函数。
· 一条都没有（0 个调用点）⇒ rc=2：判据看不见东西时不许报绿。

用法：python3 scripts/check_admin_irreversible_write_confirm.py [--self-test] [仓库根]
退出码：0 = 全部有确认；1 = 有裸口 / 名单里的调用点消失了；2 = 扫描根不存在或 0 调用点。
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

# 名单：API 函数名（前端 api 层）⇒ 为什么不可逆（一句话，必须能对着后端代码念出来）
IRREVERSIBLE = {
    "reviewDispatchPlan": "审核通过即下发并通知司机与用户",
    "checkDeparture": "核验通过即转执行中、订单置为已发车",
    "bindDriverVehicle": "会先解掉该司机现有的车与该车现有的司机",
    "sendNotification": "进用户消息中心，撤不回",
    "replanLeg": "换掉该段人车绑定；无备选则方案置异常并通知管理员",
}

DEFAULT_ROOT = "yudao-ui/yudao-ui-admin-vue3/src/views"

CALL_TMPL = r"\bawait\s+\w*Api\.(%s)\s*\("
TOP_FN = re.compile(r"^(?:const|let)\s+([A-Za-z_$][\w$]*)\s*=[^\n]*=>\s*\{", re.M)
TOP_FUNC = re.compile(r"^(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{", re.M)


def _read(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")


def _block_end(text, open_brace):
    """返回与 text[open_brace]=='{' 配对的 '}' 下标；跳过字符串/注释里的括号。"""
    depth = 0
    i = open_brace
    n = len(text)
    quote = None
    while i < n:
        ch = text[i]
        if quote:
            if ch == "\\":
                i += 2
                continue
            if ch == quote:
                quote = None
        elif ch == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        elif ch == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        elif ch in "'\"`":
            quote = ch
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _fn_blocks(text):
    """[{name, start, end}]：顶层箭头函数与 function 的函数体区间。"""
    out = []
    for m in list(TOP_FN.finditer(text)) + list(TOP_FUNC.finditer(text)):
        ob = text.index("{", m.start())
        en = _block_end(text, ob)
        if en > ob:
            out.append({"name": m.group(1), "start": ob, "end": en})
    return out


def _enclosing(blocks, pos):
    """包住 pos 的最小函数体；没有就返回 None（顶层语句里的调用点）。"""
    cands = [b for b in blocks if b["start"] <= pos <= b["end"]]
    if not cands:
        return None
    return min(cands, key=lambda b: b["end"] - b["start"])


def scan(root):
    """返回 (调用点列表, 裸口列表)。每项 = (相对路径, 行号, api 名, 函数名或 None, 有无 confirm)"""
    call_re = re.compile(CALL_TMPL % "|".join(sorted(IRREVERSIBLE)))
    hits, bare = [], []
    for dirpath, _d, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".vue"):
                continue
            path = os.path.join(dirpath, name)
            text = _read(path)
            rel = os.path.relpath(path, root).replace("\\", "/")
            blocks = _fn_blocks(text)
            for m in call_re.finditer(text):
                api = m.group(1)
                fn = _enclosing(blocks, m.start())
                has_confirm = bool(fn and re.search(r"\bconfirm\s*\(", text[fn["start"] : fn["end"]]))
                line = text.count("\n", 0, m.start()) + 1
                hits.append((rel, line, api, fn["name"] if fn else None, has_confirm))
                if not has_confirm:
                    bare.append((rel, line, api, fn["name"] if fn else None))
    return hits, bare


def main(argv):
    self_test = "--self-test" in argv
    args = [a for a in argv if a != "--self-test"]
    root = args[0] if args else DEFAULT_ROOT
    if self_test:
        return run_self_test()
    if not os.path.isdir(root):
        print("✘ 扫描根不存在：%s" % root)
        return 2
    hits, bare = scan(root)
    if not hits:
        print("✘ 名单里 %d 个写口一处都没数到 ⇒ 不能读成'都有确认'（调用点改名了？扫描根换了？）" % len(IRREVERSIBLE))
        return 2
    seen = set(h[2] for h in hits)
    missing = sorted(set(IRREVERSIBLE) - seen)
    print("[扫描] 不可逆写口调用点 %d 处 / 覆盖名单 %d/%d" % (len(hits), len(seen), len(IRREVERSIBLE)))
    for rel, line, api, fn, ok in hits:
        print("  %s%s:%d %s（%s）⇒ %s" % ("✔" if ok else "✘", rel, line, api, fn or "顶层语句",
                                          "有确认" if ok else "裸口"))
    rc = 0
    if missing:
        print("✘ 名单里这些 API 现在**没有调用点**：%s ⇒ 名单要跟着现实改（带着原因），不许留装饰" % "、".join(missing))
        rc = 1
    if bare:
        print("✘ %d 处裸口（点下去就执行，撤销不回来）" % len(bare))
        rc = 1
    if rc == 0:
        print("✅ %d 处不可逆写口全部带前置确认" % len(hits))
    return rc


SAMPLE = """<script setup lang="ts">
const submitBind = async () => {
  await message.confirm('确认绑定？')
  await DriverVehicleApi.bindDriverVehicle({ driverId, vehicleId })
}
const submitReview = async () => {
  await DispatchApi.reviewDispatchPlan(reviewForm.value)
}
</script>
"""


def run_self_test():
    """knife 逐条点名：每条只可能咬自己那一格，且必须先证明补丁真落了字节。"""
    import shutil
    import tempfile

    tmp = tempfile.mkdtemp(prefix="irwb-")
    rc = 0
    try:
        d = os.path.join(tmp, "views", "x")
        os.makedirs(d)
        p = os.path.join(d, "a.vue")
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(SAMPLE)
        hits, bare = scan(os.path.join(tmp, "views"))
        # K1 两个调用点都数到（一个有确认、一个裸）
        if len(hits) != 2 or len(bare) != 1:
            print("✘ K1 调用点/裸口计数不对：hits=%d bare=%d" % (len(hits), len(bare)))
            rc = 1
        else:
            print("✔ K1 两个调用点都数到，裸口恰好 1（bind 被 confirm 放过、review 判红）")
        # K2 裸口点名的是 review，不是别的
        if bare and bare[0][2] != "reviewDispatchPlan":
            print("✘ K2 裸口点名错了：%s" % (bare[0],))
            rc = 1
        elif bare:
            print("✔ K2 裸口点名 reviewDispatchPlan（在 submitReview 里）")
        # K3 补上 confirm ⇒ 裸口归零（同一条判据，两个方向都要对）
        patched = SAMPLE.replace(
            "const submitReview = async () => {\n",
            "const submitReview = async () => {\n  await ElMessageBox.confirm('确认通过？')\n",
        )
        if patched == SAMPLE:
            print("✘ K3 补丁没落字节")
            rc = 1
        else:
            with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write(patched)
            _h2, bare2 = scan(os.path.join(tmp, "views"))
            if bare2:
                print("✘ K3 补了 confirm 还判裸：%s" % (bare2,))
                rc = 1
            else:
                print("✔ K3 补 confirm 之后裸口归零")
        # K4 确认写在**函数体外**不算（判落点不判"文件里出现过 confirm"）
        outside = SAMPLE.replace("  await message.confirm('确认绑定？')\n", "").replace(
            "<script setup lang=\"ts\">\n", "<script setup lang=\"ts\">\nawait message.confirm('在外面')\n", 1
        )
        with io.open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(outside)
        _h3, bare3 = scan(os.path.join(tmp, "views"))
        apis3 = sorted(b[2] for b in bare3)
        if apis3 != ["bindDriverVehicle", "reviewDispatchPlan"]:
            print("✘ K4 函数体外的 confirm 被算进来了：裸口=%s" % (apis3,))
            rc = 1
        else:
            print("✔ K4 函数体外的 confirm 不算（两处都判裸）")
        # K5 真树：名单里每个 API 都必须还有调用点（名单与现实对齐）
        if os.path.isdir(DEFAULT_ROOT):
            hits5, bare5 = scan(DEFAULT_ROOT)
            seen5 = set(h[2] for h in hits5)
            miss5 = sorted(set(IRREVERSIBLE) - seen5)
            if miss5:
                print("✘ K5 真树里这些 API 没有调用点：%s" % ("、".join(miss5),))
                rc = 1
            elif bare5:
                print("✘ K5 真树里还有 %d 处裸口：%s" % (len(bare5), [(b[0], b[2]) for b in bare5]))
                rc = 1
            else:
                print("✔ K5 真树 %d 处调用点全覆盖名单且全部有确认" % len(hits5))
        else:
            print("! K5 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
