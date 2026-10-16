#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后台写操作按钮的"在飞保护"静态闸（队列 #75 / 台账 D-128）。

要防的两件事，都是"点了两次就真写了两遍"：
· 对话框底部的「发送 / 确定」按钮没绑 `:loading` 也没绑 `:disabled` ⇒ 请求还在飞，按钮照旧可点；
  后端这 40 个写接口没有一个 `@Idempotent`，双击就是两条真记录（#67 追后端那一半）。
· 声明了 `xxxLoading` 却从未出现在模板里 ⇒ 代码看着有保护，实际没人绑（dispatch 的 collectLoading
  就是这一格：`:624` 声明、`:644` 赋值，模板只绑了 `:disabled="collectDisabled"`）。
  **"保护逻辑写在一处、绑定漏在另一处"是静默的**，比根本没写更难发现。

口径：
· 只看 transport 域（本仓自研页面），上游 system/infra 不在本轮范围。
· "写按钮"= `<el-button type="primary">` 且其 @click 处理函数以 submit/send/do/save/confirm 开头，
  且该按钮位于 `<Dialog ...>` 之内或带 `v-hasPermi` 的写权限位。
· 判定"有保护"= 同一按钮元素上出现 `:loading="…Loading…"`，或 `:disabled="…Loading…"`。
  ⚠ 只要求"绑了 :disabled"是不够的，而且恰恰放过了本仓真实的那一格：dispatch 的归集按钮写着
  `:disabled="collectDisabled"`（按"选了几行"来禁用），看起来有保护，实际在飞期间照样能再点一次，
  而同一页声明的 `collectLoading` 从没接到按钮上。判据必须认"**绑的是在飞标志**"，不是"绑了个禁用条件"。
· 死变量 = 文件里 `const X = ref(false)` 且名字以 Loading 结尾，而模板部分（`<template>` 到 `</template>`）
  里一次都没出现 `X`。

用法：
    python3 scripts/check_admin_write_button_guard.py [--report-only] [扫描根]
退出码：0 = 全部达标；1 = 有未达标（逐条列 文件:行）；2 = 扫描根不存在 / 一个写按钮都没数到（判据没跑起来）。
"""
import io
import os
import re
import sys

DEFAULT_ROOT = os.path.join("yudao-ui", "yudao-ui-admin-vue3", "src", "views", "transport")
WRITE_VERB = re.compile(r'^(submit|send|do|save|confirm)[A-Z]')
# 有/无类型标注两种写法都要认：`const xLoading = ref(false)` 与 `const xLoading = ref<boolean>(false)`
LOADING_DECL = re.compile(r'const\s+(\w*Loading)\s*(?::[^=]*)?=\s*ref\s*[<(]')
CLICK = re.compile(r'@click="([A-Za-z_$][\w$]*)\(?[^"]*"')


def blocks_of(text, open_pat, close_str):
    """粗粒度取出元素块（按出现顺序，允许嵌套同名标签时不要求精确配平）。"""
    out = []
    for m in re.finditer(open_pat, text):
        end = text.find(close_str, m.end())
        if end < 0:
            continue
        out.append((text[:m.start()].count("\n") + 1, text[m.start():end + len(close_str)]))
    return out


def scan(path):
    text = io.open(path, encoding="utf-8", errors="replace").read()
    # ⚠ 不能用 `text.split("</template>")[0]`：这些页面里有 `<template #default="scope">` 的列插槽，
    #   第一个 `</template>` 是**内层**的闭合标签，模板会被截断在半路 —— 我第一版就是这么把
    #   notification/handover 两个真问题扫成了"没找到"，闸变成一台永远绿的空转判据。
    cut = text.find("<script")
    tpl = text[:cut] if cut > 0 else text
    script = text[cut:] if cut > 0 else ""
    bad_btn, dead = [], []
    counted = 0
    for ln, blk in blocks_of(tpl, r'<el-button\b', '</el-button>'):
        m = CLICK.search(blk)
        if not m:
            continue
        fn = m.group(1)
        if not WRITE_VERB.match(fn):
            continue
        if 'type="primary"' not in blk:
            continue
        counted += 1
        if not re.search(r':(?:loading|disabled)="[^"]*Loading', blk):
            bad_btn.append((ln, fn, "按钮没绑在飞标志（:loading/:disabled 里没有出现 *Loading 变量）"))
    for name in set(LOADING_DECL.findall(script)):
        if not re.search(r'\b%s\b' % re.escape(name), tpl):
            bad = True
            dead.append(name)
    return bad_btn, dead, counted


def main():
    args = [a for a in sys.argv[1:] if a != "--report-only"]
    report_only = "--report-only" in sys.argv[1:]
    root = args[0] if args else DEFAULT_ROOT
    if not os.path.isdir(root):
        sys.stderr.write("扫描根不存在：%s\n" % root)
        return 2
    bad_btn, dead_total, counted, files = [], [], 0, 0
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in sorted(dirnames) if d != "__tests__"]
        for fn in sorted(filenames):
            if not fn.endswith(".vue"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            b, d, n = scan(path)
            files += 1
            counted += n
            for ln, handler, why in b:
                bad_btn.append((rel, ln, handler, why))
            for name in d:
                dead_total.append((rel, name))
    print("[扫描] %s ⇒ %d 个页面，写按钮 %d 个，Loading 死变量 %d 个"
          % (root, files, counted, len(dead_total)))
    if counted == 0:
        sys.stderr.write("一个写按钮都没数到 —— 判据没跑起来，不许读成“全部有保护”\n")
        return 2
    for rel, ln, handler, why in bad_btn:
        print("× %s:%d  @click=%s —— %s" % (rel, ln, handler, why))
    for rel, name in dead_total:
        print("× %s  %s 声明了但模板里从没绑（保护逻辑没接到按钮上）" % (rel, name))
    if not bad_btn and not dead_total:
        print("✅ %d 个写按钮都在飞保护里，且没有孤儿 Loading 变量" % counted)
        return 0
    return 0 if report_only else 1


if __name__ == "__main__":
    sys.exit(main())
