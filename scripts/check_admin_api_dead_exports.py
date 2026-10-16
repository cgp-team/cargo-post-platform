#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""api 层死导出与"同一个端点两个出口"清点（队列 #68 / 台账 D-134）。

两件事都要判，因为它们坏法不同：
· **死导出**：`export const getXxx = ...` 在整个 src 里没有任何引用点。它不报错，只让下一轮的人
  以为"这条通路已经接好了"（后端有端点、前端有函数、页面没调 = 三面看起来都对，用户看不见）。
· **重复出口**：两个导出打**同一个 method+url**。这才是"会丢契约"的那一格 ——
  有人改了 A 而页面用的是 B，或反过来：接口变了，只有一处跟着变，另一处替旧契约背书。
  实测过一例：`notification/index.ts::getOrderEventTimeline` 与 `orderEvent/index.ts::getOrderEvents`
  打的都是 `/transport/order-event/list-by-order`，前者零引用。

口径：
· 只扫 `src/api/transport/**/*.ts` 里 `export const NAME = ... request.<method>({ url: '…' })` 这一形
  （`request.get({ url })` / `await request.post({ url })` 都认）。
· 引用判定：符号名在**任意** src 文件里出现过（除它自己的声明行）⇒ 算有引用。
  命名空间导入（`import * as DispatchApi` 后 `DispatchApi.getDispatchPlan()`）就是靠符号名命中的。
· 解析到的导出数为 0 ⇒ rc=2："一个都没数到"不能读成"都干净"。

用法：python3 scripts/check_admin_api_dead_exports.py [--self-test] [前端根]
退出码：0 = 死导出与重复出口都归零；1 = 有死导出或重复出口；2 = 目录不存在 / 解析到 0 个导出。
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
import json
import os
import re
import sys

DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src")
API_SUB = os.path.join("api", "transport")

EXPORT_RE = re.compile(r"^export\s+(?:async\s+)?(?:const|function)\s+(\w+)", re.M)
REQ_RE = re.compile(r"request\s*\.\s*(get|post|put|delete)\s*\(\s*\{[^}]*?url\s*:\s*[`'\"]([^`'\"]+)", re.S)


def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")


def collect_exports(api_dir):
    """[{name, file, method, url, decl_line}]"""
    out = []
    for dirpath, _d, files in os.walk(api_dir):
        for name in sorted(files):
            if not name.endswith(".ts"):
                continue
            p = os.path.join(dirpath, name)
            text = read(p)
            for m in EXPORT_RE.finditer(text):
                seg = text[m.start():]
                nxt = EXPORT_RE.search(seg, 1)
                body = seg[: nxt.start()] if nxt else seg
                rm = REQ_RE.search(body)
                out.append(
                    {
                        "name": m.group(1),
                        "file": p,
                        "method": rm.group(1).lower() if rm else "",
                        "url": rm.group(2) if rm else "",
                        "decl_line": text.count("\n", 0, m.start()) + 1,
                    }
                )
    return out


def usage_counts(exports, src_files):
    """返回 {name: 引用文件数}；同文件里除声明行外的出现也算引用。"""
    counts = {e["name"]: 0 for e in exports}
    by_file = {}
    for e in exports:
        by_file.setdefault(e["file"], []).append(e["name"])
    for f, content in src_files.items():
        lines = content.split("\n")
        for name in counts:
            own = f in by_file and name in by_file[f]
            hit = False
            for i, line in enumerate(lines):
                if not re.search(r"\b%s\b" % re.escape(name), line):
                    continue
                if own and re.match(r"^\s*export\s+(?:async\s+)?(?:const|function)\s+%s\b" % re.escape(name), line):
                    continue  # 只剔掉声明行本身
                hit = True
                break
            if hit:
                counts[name] += 1
    return counts


def duplicates(exports):
    """同一 (method, url) 被多个导出声明 ⇒ 重复出口。url 去掉尾部查询串再比。"""
    groups = {}
    for e in exports:
        if not e["url"]:
            continue
        key = (e["method"], e["url"].split("?")[0].rstrip("/"))
        groups.setdefault(key, []).append(e)
    return {k: v for k, v in groups.items() if len(v) > 1}


def scan(root):
    api_dir = os.path.join(root, API_SUB)
    if not os.path.isdir(api_dir):
        return None, None, None, None
    exports = collect_exports(api_dir)
    src_files = {}
    for dirpath, _d, files in os.walk(root):
        for name in files:
            if name.endswith((".ts", ".vue", ".js")):
                p = os.path.join(dirpath, name)
                src_files[p] = read(p)
    counts = usage_counts(exports, src_files)
    dead = [e for e in exports if counts[e["name"]] == 0]
    dup = duplicates(exports)
    return exports, dead, dup, counts


def main(argv):
    self_test = "--self-test" in argv
    args = [a for a in argv if a != "--self-test"]
    root = args[0] if args else DEFAULT_ROOT
    if self_test:
        return run_self_test()
    if not os.path.isdir(os.path.join(root, API_SUB)):
        print("✘ 找不到 api 目录：%s" % os.path.join(root, API_SUB))
        return 2
    exports, dead, dup, _c = scan(root)
    if not exports:
        print("✘ 解析到 0 个导出 ⇒ 不能读成「没有死导出」（提取器或目录变了）")
        return 2
    print("[扫描] api/transport 导出 %d 个；零引用 %d 个；重复出口组 %d 组" % (len(exports), len(dead), len(dup)))
    for e in dead:
        print("  ✘ 死导出 %s（%s:%d，%s %s）" % (e["name"], e["file"], e["decl_line"], e["method"] or "?", e["url"] or "?"))
    for (method, url), group in sorted(dup.items()):
        print("  ✘ 重复出口 %s %s ⇒ %s" % (method.upper(), url, "、".join("%s:%d" % (g["name"], g["decl_line"]) for g in group)))
    if dead or dup:
        return 1
    print("✅ api 层没有零引用导出，也没有同一端点两个出口")
    return 0


FIXTURE_DEAD = """import request from '@/config/axios'
export const getXxxPage = (params: any) => request.get({ url: '/transport/xxx/page', params })
export const neverUsed = (id: number) => request.get({ url: '/transport/xxx/get', params: { id } })
"""
FIXTURE_USED = """import request from '@/config/axios'
export const getXxxPage = (params: any) => request.get({ url: '/transport/xxx/page', params })
"""
FIXTURE_PAGE = """<script setup lang="ts">
import * as XxxApi from '@/api/transport/xxx'
const load = async () => { const r = await XxxApi.getXxxPage({ pageNo: 1 }) }
</script>
"""
FIXTURE_DUP_A = """import request from '@/config/axios'
export const timelineA = (orderId: number) => request.get({ url: '/transport/order-event/list-by-order', params: { orderId } })
"""
FIXTURE_DUP_B = """import request from '@/config/axios'
export const timelineB = (orderId: number) => request.get({ url: '/transport/order-event/list-by-order?orderId=' + orderId })
"""
FIXTURE_USED2 = """import { timelineA } from '@/api/transport/a'
const go = () => timelineA(1)
"""


def run_self_test():
    """六把刀：死导出要数到、命名空间调用要算引用、同 url 要成组、真树要对得上。"""
    import shutil
    import tempfile

    tmp = tempfile.mkdtemp(prefix="deadexp-")
    rc = 0
    try:
        def mk(rel, text):
            p = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)

        def snapshot():
            # scan 返四值（exports, dead, dup, counts）；knife 也要按四值接，
            # 少接一个就是 ValueError —— 上一版正是这样在自检里崩的（崩在 K1 之前，四条刀都没跑）
            return scan(tmp)

        # K1 零引用被数到
        mk(os.path.join("api", "transport", "xxx", "index.ts"), FIXTURE_DEAD)
        exports, dead, dup, _counts = snapshot()
        names = sorted(e["name"] for e in dead)
        # K1 这一刻还没有任何页面：两个导出**都**该判死（期望写成一个才是错的 —— 上一版我就是这么写红的）
        if names != ["getXxxPage", "neverUsed"]:
            print("✘ K1 死导出清点不对：%s" % (names,))
            rc = 1
        else:
            print("✔ K1 没有消费者时两个导出都判死（own 文件那格只剔声明行）")

        # K2 命名空间调用（XxxApi.getXxxPage）必须算引用
        mk(os.path.join("views", "transport", "xxx", "index.vue"), FIXTURE_PAGE)
        _e2, dead2, _d2, _c2 = snapshot()
        names2 = sorted(e["name"] for e in dead2)
        if names2 != ["neverUsed"]:
            print("✘ K2 命名空间调用没算成引用：死清单=%s" % (names2,))
            rc = 1
        else:
            print("✔ K2 `XxxApi.getXxxPage()` 算引用（同文件声明行不算）")

        # K3 同 method+url 两个导出 ⇒ 成组
        mk(os.path.join("api", "transport", "a", "index.ts"), FIXTURE_DUP_A)
        mk(os.path.join("api", "transport", "b", "index.ts"), FIXTURE_DUP_B)
        _e3, dead3, dup3, _c3 = snapshot()
        keys = sorted("%s %s" % k for k in dup3)
        if keys != ["get /transport/order-event/list-by-order"]:
            print("✘ K3 重复出口没成组：%s" % (keys,))
            rc = 1
        else:
            print("✔ K3 同一端点两个出口被成组（含拼进查询串那形）")

        # K4 有引用（直接 import）⇒ 不在死清单里
        mk(os.path.join("views", "transport", "use", "index.vue"), FIXTURE_USED2)
        _e4, dead4, _dup4, _c4 = snapshot()
        if "timelineA" in [e["name"] for e in dead4]:
            print("✘ K4 timelineA 被直接 import 还判死")
            rc = 1
        else:
            print("✔ K4 直接 import 的 timelineA 不算死（timelineB 仍在死清单）")

        # K5 空 api 目录 ⇒ 必须 rc=2 而不是"都干净"
        empty = tempfile.mkdtemp(prefix="deadexp-empty-")
        os.makedirs(os.path.join(empty, "api", "transport"), exist_ok=True)
        exports5, _d5, _u5, _c5 = scan(empty)
        if exports5:
            print("✘ K5 空目录却数到导出：%d 个" % len(exports5))
            rc = 1
        else:
            print("✔ K5 空目录数到 0 个（main 会把这一格判 rc=2，不当绿）")
        shutil.rmtree(empty, ignore_errors=True)

        # K6 真树读数必须点名（这条把"自检过了"钉在当前这棵树上）
        if os.path.isdir(os.path.join(DEFAULT_ROOT, API_SUB)):
            exports6, dead6, dup6, _c6 = scan(DEFAULT_ROOT)
            print("✔ K6 真树：导出 %d 个 / 死导出 %d 个 / 重复出口 %d 组"
                  % (len(exports6), len(dead6), len(dup6)))
            if dead6 or dup6:
                print("✘ K6 真树还没归零：%s" % json.dumps(
                    {"dead": [e["name"] for e in dead6], "dup": [list(k) for k in dup6]}, ensure_ascii=False))
                rc = 1
        else:
            print("! K6 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
