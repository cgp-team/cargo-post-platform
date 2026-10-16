#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后台"搜索必须先回第 1 页"静态闸（队列 #74 / 台账 D-128）。

要防的那件事：列表页翻到第 5 页，改一下筛选条件点"搜索"，请求仍带 `pageNo=5`。
新条件下往往没有第 5 页 ⇒ 表格空、页面念"暂无数据"，运营读成"这个条件下没有单"，
而真实结果就在第 1 页。**"重置"是好的、"搜索"是坏的**，两条路径行为不一致，而且没有任何报错。

口径：
· 只管**真的分页**的页面：从 `<Pagination v-model:page="X.pageNo">` 读出这个页面在用一个或多个
  查询对象（调度中心一页两套分页就是两个）。没有分页的页面（如拓扑图）不参与，免得把闸做成误红。
· "搜索入口"有三种写法，都要查：按钮 `@click="fn"`（含 `ep:search` 图标，可能跨行）、
  输入框 `@keyup.enter="fn"`、以及 `el-form` 上的 `@submit.prevent="fn"` —— 浏览器里回车首先触发的是
  **表单提交**，只改按钮和 input 上的 keyup 等于留第三条不一致的路径。
· 处理函数体（从定义处按括号配平取出，**收在同一行也立刻停**）里必须出现
  `X.pageNo = 1`（X 属于本页的分页查询对象集合），否则记为未达标。

用法：
    python3 scripts/check_admin_query_page_reset.py                # 扫默认根
    python3 scripts/check_admin_query_page_reset.py --report-only  # 只点名，不判红
    python3 scripts/check_admin_query_page_reset.py --self-test    # 八把刀各占一个目录
退出码：0 = 全部达标；1 = 有未达标页面（逐条列出 文件:行 与函数名）；
        2 = 扫描根不存在 / 一个搜索入口都没数到（"数到 0"绝不能读成"没有问题"）。
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

# 只管本仓自研的 transport 域：上游 system/infra 那些页面不在本轮范围里，
# 把它们一起钉成闸会让 CI 永远红（而"红到没人管"的闸等于没有闸）。
DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views", "transport")
PAGING = re.compile(r'v-model:page="([A-Za-z_$][\w$]*)\.pageNo"')
CLICK = re.compile(r'@click="([A-Za-z_$][\w$]*)"')
ENTER = re.compile(r'@keyup\.enter="([A-Za-z_$][\w$]*)"')
# 第三种入口：el-form 上的 @submit.prevent。输入框回车在浏览器里首先触发的是 **表单提交**，
# 只改按钮和 input 上的 keyup，这条路照旧带着旧页码发请求
SUBMIT = re.compile(r'@submit\.prevent="([A-Za-z_$][\w$]*)"')
DEF_TPL = r'(?:const|let)\s+%s\s*=\s*(?:async\s*)?\([^)]*\)\s*=>|function\s+%s\s*\([^)]*\)\s*\{'


def read_lines(path):
    return io.open(path, encoding="utf-8", errors="replace").read().split("\n")


def def_line(lines, name):
    pat = re.compile(DEF_TPL % (re.escape(name), re.escape(name)))
    for i, l in enumerate(lines):
        if pat.search(l):
            return i
    return None


def body_of(lines, start):
    """从第 start 行起按字符配平取出函数体（字符串/模板串里的括号跳过）。"""
    text = "\n".join(lines[start:])
    try:
        i = text.index("{")
    except ValueError:
        return ""
    depth, instr, j = 0, None, i
    while j < len(text):
        ch = text[j]
        if instr:
            if ch == "\\":
                j += 2
                continue
            if ch == instr:
                instr = None
        elif ch in "'\"`":
            instr = ch
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
        j += 1
    return text[i:]


def button_targets(lines):
    """搜索按钮：`<el-button` 起始到 `</el-button>`（或自闭合行尾）之间含 ep:search 的，取其 @click。

    按钮常写成跨两行（图标在子节点里），所以按元素块取，不按单行取。
    """
    out = []
    block = None
    for i, l in enumerate(lines):
        if block is None and "<el-button" in l:
            block = [i, l]
            if "</el-button>" in l or l.rstrip().endswith(">"):
                pass
        elif block is not None:
            block[1] += "\n" + l
        if block is not None and ("</el-button>" in block[1] or block[1].count("\n") > 4):
            if "ep:search" in block[1]:
                for m in CLICK.finditer(block[1]):
                    out.append((block[0] + 1, m.group(1), "搜索按钮"))
            block = None
    for i, l in enumerate(lines):
        for m in ENTER.finditer(l):
            out.append((i + 1, m.group(1), "回车"))
        for m in SUBMIT.finditer(l):
            out.append((i + 1, m.group(1), "表单提交"))
    return out


def scan_file(path):
    lines = read_lines(path)
    qobjs = {m.group(1) for m in PAGING.finditer("\n".join(lines))}
    if not qobjs:
        return None
    hits = []
    for ln, name, kind in button_targets(lines):
        start = def_line(lines, name)
        if start is None:
            hits.append((ln, name, kind, "找不到无参处理函数的定义"))
            continue
        body = body_of(lines, start)
        ok = any(re.search(r'\b%s\.pageNo\s*=\s*1\b' % re.escape(q), body) for q in qobjs)
        if not ok:
            hits.append((ln, name, kind, "函数体里没有 pageNo 复位（本页分页对象：%s）" % "/".join(sorted(qobjs))))
    return hits


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

    bad, entries, pages_with_paging = [], 0, 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d not in ("__tests__", "node_modules")]
        for fn in sorted(filenames):
            if not fn.endswith(".vue"):
                continue
            path = os.path.join(dirpath, fn)
            hits = scan_file(path)
            if hits is None:
                continue
            pages_with_paging += 1
            entries += len(button_targets(read_lines(path)))
            for ln, name, kind, why in hits:
                bad.append((os.path.relpath(path, root).replace(os.sep, "/"), ln, name, kind, why))

    print("[扫描] %s ⇒ 分页页面 %d 个，搜索入口 %d 处（按钮 + 回车 + 表单提交）"
          % (root, pages_with_paging, entries))
    if entries == 0:
        sys.stderr.write("一个搜索入口都没数到 —— 判据没跑起来，不许读成“全部达标”\n")
        return 2
    if not bad:
        print("✅ %d 处搜索入口全部先回第 1 页" % entries)
        return 0
    for rel, ln, name, kind, why in bad:
        print("× %s:%d  @%s=%s —— %s" % (rel, ln, kind, name, why))
    print("❌ %d 处搜索入口带着旧页码发请求（共 %d 处）" % (len(bad), entries))
    return 0 if report_only else 1


# ---------------- 自检（八把刀，一条一个目录：同目录放多条 fixture 会让读数互相顶包） ----------------
# 每把刀对的是这本的一个具体坏法，不是"跑一下没报错"。

FX_BAD = '''<template>
  <Pagination v-model:page="query.pageNo" :page-size="10" />
  <el-button type="primary" @click="handleSearch"><Icon icon="ep:search" />搜索</el-button>
</template>
<script setup lang="ts">
const query = reactive({ pageNo: 1, keyword: "" })
const handleSearch = async () => {
  await Api.page(query)
}
</script>
'''

FX_GOOD = FX_BAD.replace("  await Api.page(query)", "  query.pageNo = 1\n  await Api.page(query)")

FX_MULTI = FX_BAD.replace(
    '<el-button type="primary" @click="handleSearch"><Icon icon="ep:search" />搜索</el-button>',
    '<el-button type="primary" @click="handleSearch">\n    <Icon icon="ep:search" />\n    搜索\n  </el-button>')

FX_ONELINE = FX_GOOD.replace(
    'const handleSearch = async () => {\n  query.pageNo = 1\n  await Api.page(query)\n}',
    'const handleSearch = async () => { query.pageNo = 1; await Api.page(query) }')

FX_ENTER_SUBMIT = '''<template>
  <Pagination v-model:page="q.pageNo" />
  <el-input v-model="q.keyword" @keyup.enter="doSearch" />
  <el-form @submit.prevent="doSubmit">
    <el-button type="primary"><Icon icon="ep:search" /></el-button>
  </el-form>
</template>
<script setup lang="ts">
const q = reactive({ pageNo: 1, keyword: "" })
const doSearch = () => { fetch(q) }
const doSubmit = () => { fetch(q) }
</script>
'''

FX_NOPAGING = FX_BAD.replace('<Pagination v-model:page="query.pageNo" :page-size="10" />\n  ', "")


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix="qpres-")
    cases = [
        ("K1 带旧页码的搜索入口必须被数到", FX_BAD, 1),
        ("K2 复位的写法不算违规", FX_GOOD, 0),
        ("K3 回车与表单提交两条路径都要查（只做按钮=窄化）", FX_ENTER_SUBMIT, 2),
        ("K4 跨行按钮（图标在子节点）仍认得出搜索入口", FX_MULTI, 1),
        ("K5 函数体收在同一行也要取出来（别误报成找不到定义）", FX_ONELINE, 0),
        ("K6 没有分页的页面不参与（免得闸做成误红）", FX_NOPAGING, 0),
    ]
    try:
        for i, (name, body, want) in enumerate(cases):
            d = os.path.join(tmp, "k%d" % (i + 1))
            os.makedirs(d, exist_ok=True)
            p = os.path.join(d, "a.vue")
            with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            hits = scan_file(p)
            n = 0 if hits is None else len(hits)
            if hits is None and want == 0 and name.startswith("K6"):
                print("✔ %s（该页无分页，整页不参与）" % name)
                continue
            if n != want:
                print("✘ %s：期望 %d 处，实得 %d（%s）" % (name, want, n, [h[3][:28] for h in (hits or [])]))
                rc = 1
            else:
                print("✔ %s（%d 处）" % (name, n))
        # K7 扫描面里一个入口都没有 ⇒ main 必须走 rc=2，不许读成"全部达标"
        d7 = os.path.join(tmp, "k7")
        os.makedirs(d7, exist_ok=True)
        with io.open(os.path.join(d7, "b.vue"), "w", encoding="utf-8", newline="\n") as f:
            f.write("<template><div>没有分页也没有搜索</div></template>\n")
        argv_bak = sys.argv
        sys.argv = ["x", d7]
        try:
            got7 = main()
        finally:
            sys.argv = argv_bak
        if got7 != 2:
            print("✘ K7 空样本 rc=%d（期望 2：数到 0 不是达标）" % got7)
            rc = 1
        else:
            print("✔ K7 空样本走 rc=2 出口")
        # K8 真树：必须 rc=0 且对照数>0（这条把"自检过了"钉在真产物上）
        if os.path.isdir(DEFAULT_ROOT):
            argv_bak = sys.argv
            sys.argv = ["x"]
            try:
                got8 = main()
            finally:
                sys.argv = argv_bak
            if got8 != 0:
                print("✘ K8 真树 rc=%d" % got8)
                rc = 1
            else:
                print("✔ K8 真树达标（见上一行读数）")
        else:
            print("! K8 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())
