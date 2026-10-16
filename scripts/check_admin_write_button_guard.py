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
    python3 scripts/check_admin_write_button_guard.py --self-test    # 九把刀各占一个目录
退出码：0 = 全部达标；1 = 有未达标（逐条列 文件:行）；2 = 扫描根不存在 / 一个写按钮都没数到（判据没跑起来）。
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
    argv = sys.argv[1:]
    if "--self-test" in argv:
        return run_self_test()
    args = [a for a in argv if a != "--report-only"]
    report_only = "--report-only" in argv
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


# ---------------- 自检（九把刀，一条一个目录） ----------------
# 这的一本的历史 bug 在 K6：模板里有内层 `<template #default>` 列插槽时，
# 按 `split("</template>")[0]` 取模板会把页面截断在半路 —— 真问题扫不出来，闸永远绿。

FX_NO_GUARD = '''<template>
  <Dialog title="发送通知">
    <el-button type="primary" @click="submitSend">发送</el-button>
  </Dialog>
</template>
<script setup lang="ts">
const submitSend = async () => {}
</script>
'''

FX_LOADING_BOUND = FX_NO_GUARD.replace('@click="submitSend"', '@click="submitSend" :loading="sendLoading"')
FX_DISABLED_BOUND = FX_NO_GUARD.replace('@click="submitSend"', '@click="submitSend" :disabled="sendLoading"')
FX_FAKE_GUARD = FX_NO_GUARD.replace('@click="submitSend"', '@click="submitSend" :disabled="collectDisabled"')
FX_DEAD_VAR = FX_NO_GUARD.replace('@click="submitSend"', '@click="submitSend" :loading="sendLoading"').replace(
    "const submitSend = async () => {}",
    "const sendLoading = ref(false)\nconst submitSend = async () => { sendLoading.value = true }")
# K6 的 fixture 把按钮**放进列插槽里**（页面上多出一个内层 `</template>`）：
# 旧写法 `text.split("</template>")[0]` 会在这里把模板截断，按钮直接消失，闸永远绿。
FX_SLOT = FX_NO_GUARD.replace(
    '<el-button type="primary" @click="submitSend">发送</el-button>',
    '<el-table-column label="操作"><template #default="scope">\n'
    '      <el-button type="primary" @click="submitSend">发送</el-button>\n'
    '    </template></el-table-column>')
FX_READONLY = FX_NO_GUARD.replace('type="primary" @click="submitSend"', '@click="getList"')


def run_self_test():
    import shutil
    import tempfile

    rc = 0
    tmp = tempfile.mkdtemp(prefix="btn-")
    # (名字, fixture, 期望的按钮违规数, 期望数到的写按钮数, 期望的死变量数)
    cases = [
        ("K1 写按钮没绑在飞标志必须被数到", FX_NO_GUARD, 1, 1, 0),
        ("K2 绑了 :loading 达标", FX_LOADING_BOUND, 0, 1, 0),
        ("K3 绑了 :disabled=\"xLoading\" 同样达标", FX_DISABLED_BOUND, 0, 1, 0),
        ("K4 只绑一个按行数禁用的 :disabled 仍算没保护（本仓真那一格）", FX_FAKE_GUARD, 1, 1, 0),
        ("K5 Loading 绑上了就不算死变量；绑与没绑两条各数一次", FX_DEAD_VAR, 0, 1, 0),
        ("K6 按钮在内层列插槽里也必须被数到（历史截断 bug）", FX_SLOT, 1, 1, 0),
        ("K7 非写动词的按钮不计入", FX_READONLY, 0, 0, 0),
    ]
    try:
        for i, (name, body, want_bad, want_counted, want_dead) in enumerate(cases):
            d = os.path.join(tmp, "k%d" % (i + 1))
            os.makedirs(d, exist_ok=True)
            p = os.path.join(d, "a.vue")
            with io.open(p, "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            b, dead, counted = scan(p)
            if len(b) != want_bad or counted != want_counted or len(dead) != want_dead:
                print("✘ %s：按钮违规 %d/%d、写按钮 %d/%d、死变量 %d/%d"
                      % (name, len(b), want_bad, counted, want_counted, len(dead), want_dead))
                rc = 1
            else:
                print("✔ %s（违规 %d 处 / 写按钮 %d 个 / 死变量 %d 个）" % (name, len(b), counted, len(dead)))
        # K5b 死变量那一格单独一条：声明了 sendLoading 但按钮没绑 ⇒ 必须数到 1 个死变量
        d5b = os.path.join(tmp, "k5b")
        os.makedirs(d5b, exist_ok=True)
        p5b = os.path.join(d5b, "a.vue")
        with io.open(p5b, "w", encoding="utf-8", newline="\n") as f:
            f.write(FX_NO_GUARD.replace(
                "const submitSend = async () => {}",
                "const sendLoading = ref(false)\nconst submitSend = async () => { sendLoading.value = true }"))
        b5, dead5, c5 = scan(p5b)
        if len(dead5) != 1 or len(b5) != 1:
            print("✘ K5b 声明了却没绑：死变量 %d（期望 1）、按钮违规 %d（期望 1）%s" % (len(dead5), len(b5), dead5))
            rc = 1
        else:
            print("✔ K5b Loading 声明了但模板从没绑 ⇒ 死变量数到（%s）" % ",".join(dead5))
        # K8 一个写按钮都没数到 ⇒ main 必须 rc=2，不许读成"全部有保护"
        d8 = os.path.join(tmp, "k8")
        os.makedirs(d8, exist_ok=True)
        with io.open(os.path.join(d8, "a.vue"), "w", encoding="utf-8", newline="\n") as f:
            f.write("<template><div>只读页</div></template>\n")
        argv_bak = sys.argv
        sys.argv = ["x", d8]
        try:
            got8 = main()
        finally:
            sys.argv = argv_bak
        if got8 != 2:
            print("✘ K8 空样本 rc=%d（期望 2）" % got8)
            rc = 1
        else:
            print("✔ K8 无写按钮走 rc=2 出口")
        # K9 真树
        if os.path.isdir(DEFAULT_ROOT):
            sys.argv = ["x"]
            try:
                got9 = main()
            finally:
                sys.argv = argv_bak
            if got9 != 0:
                print("✘ K9 真树 rc=%d" % got9)
                rc = 1
            else:
                print("✔ K9 真树达标（见上一行读数）")
        else:
            print("! K9 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())
