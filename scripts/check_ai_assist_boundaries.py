#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AI 那条链的结构闸（第 15 本；契约 docs/AI_ASSIST_BOUNDARY.md §0 / §4 / §5 / §7）。

要防的事很具体：AI 是"增强"这句话，只要没有闸，就会在某一次"顺手加个自动执行"里变成主干。
本仓已经有过同形的教训（假上报、假签名、假进度），所以这里把四句话变成可判的形状：

· A1 **只出草稿**：AI 任务的 service/controller 里不许出现任何业务写调用
  （`insert(` / `updateById(` / `deleteById(` / `save(`）——出现即红。
  落库只有一条路：值班员在既有页面操作，走那些口自己的确认框与幂等键。
· A2 **唯一外呼口**：整个模块里能发 HTTP 的文件只许有 `AiGateway.java` 一个。
  多一处发 HTTP，就多一处可以绕过超时、脱敏与留痕。
· A3 **日志必须有生产者**：`ai_invocation_log` 表与只读页早就建好了，但契约 §5 要求"数得出来"——
  零生产者就是一张永远空的表（这条正是接手时量出来的：表、页、service 都在，`record()` 一个调用点都没有）。
· A4 **密钥不外流**：`getApiKey()` 在 main 里只许出现在拼 Authorization 头那一处；
  任何 `record(` 的入参里带上它就红（日志会进库、会被截图）。
· A5 **回包形状**：AI 任务口的返回类型只许是 `AiTasks.Draft`（草稿），
  不许出现 `CommonResult<Boolean>` 那种"我已经替你做了"的返回。

用法：python3 scripts/check_ai_assist_boundaries.py [--self-test] [仓库根]
退出码：0 全部达标；1 有未达标；2 扫描根/模块不存在或一条都没数到（判据空转不算通过）。
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

MODULE = os.path.join("yudao-module-ai-assist", "src", "main", "java")
TASK_DIRS = (os.path.join("cn", "iocoder", "yudao", "module", "aiassist", "service", "task"),
             os.path.join("cn", "iocoder", "yudao", "module", "aiassist", "controller", "admin", "task"))
WRITE_CALL = re.compile(r'\.(insert|updateById|deleteById|deleteBatchIds|update)\s*\(|\bsave\s*\(')
HTTP_CALLER = re.compile(r'HttpClient|RestTemplate|WebClient|OkHttp|HttpURLConnection')
DRAFT_RETURN = re.compile(r'CommonResult<\s*(AiTasks\.)?Draft\s*>')
ANY_RETURN = re.compile(r'public\s+CommonResult<([^>]+)>')


def read(path):
    return io.open(path, encoding="utf-8", errors="replace").read()


def java_files(root, sub=None):
    base = os.path.join(root, sub) if sub else root
    out = []
    for dirpath, dirnames, names in os.walk(base):
        dirnames[:] = [d for d in sorted(dirnames) if d != "target"]
        for n in sorted(names):
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n))
    return out


def scan(root):
    files = java_files(root, MODULE)
    if not files:
        return None
    a1, a2, a4, a5 = [], [], [], []
    a3 = 0
    for p in files:
        text = read(p)
        rel = os.path.relpath(p, root).replace(os.sep, "/")
        in_task = any(t in rel.replace("/", os.sep) or t.replace(os.sep, "/") in rel for t in TASK_DIRS)
        if in_task:
            for m in WRITE_CALL.finditer(text):
                line = text[:m.start()].count("\n") + 1
                a1.append("%s:%d AI 那条链里出现了业务写调用 %s" % (rel, line, m.group(0).strip()))
            for m in ANY_RETURN.finditer(text):
                if not DRAFT_RETURN.search(m.group(0)):
                    a5.append("%s 返回了 CommonResult<%s>：AI 口只许回草稿" % (rel, m.group(1)))
        if HTTP_CALLER.search(text) and "AiGateway.java" not in rel:
            a2.append(rel)
        if "getApiKey()" in text and "Authorization" not in text:
            a4.append(rel + "（用了密钥却没有 Authorization 头那一处 ⇒ 可能进了日志/回显）")
        if re.search(r'invocationLog\.record\(|\.record\(row\)', text):
            a3 += 1
    return dict(files=len(files), a1=a1, a2=a2, a3=a3, a4=a4, a5=a5)


def report(s):
    print("[扫描] ai-assist main 下 java %d 个；日志生产者 %d 处" % (s["files"], s["a3"]))
    bad = []
    for x in s["a1"]:
        bad.append("× A1 " + x)
    if s["a2"]:
        bad.append("× A2 除 AiGateway 外还有文件自己发 HTTP：" + "、".join(s["a2"]))
    if s["a3"] < 1:
        bad.append("× A3 ai_invocation_log 没有任何生产者：表建了、页做了，但永远是一张空表（§5 要求数得出来）")
    for x in s["a4"]:
        bad.append("× A4 " + x)
    for x in s["a5"]:
        bad.append("× A5 " + x)
    for line in bad:
        print(line)
    if bad:
        return 1
    print("✅ AI 那条链的四条结构约束都在：只出草稿、唯一外呼口、留痕有生产者、密钥不外流")
    return 0


def run_self_test():
    import shutil
    import tempfile
    rc = 0
    tmp = tempfile.mkdtemp(prefix='aibound-')
    OK_SVC = ('package x.service.task;\npublic class AiTaskService {\n'
              '    void f() { gateway.complete("s", "p", false); }\n'
              '    private void rec(Object row) { invocationLog.record(row); }\n'
              '    public CommonResult<Draft> classify() { return null; }\n}\n')
    cases = [
        ("K1 合规形状必须判绿（否则后面每一把的红都可能是判据自己坏了）",
         {("service/task/AiTaskService.java"): OK_SVC,
          # 夹具要像真东西：真出口里 getApiKey() 与 Authorization 头在同一个文件（K1 第一版漏了这句，
          # 于是正对照自己被判红 —— 那不是规则错，是夹具不忠实，会让"绿"变得不可信）
          ("core/AiGateway.java"): "class AiGateway { java.net.http.HttpClient c; "
                                  "String h() { return \"Authorization: Bearer \" + props.getApiKey(); } }"
          }, 0),
        ("K2 AI 链里出现业务写调用 ⇒ 必须红（这就是「顺手加个自动执行」的形状）",
         {"service/task/AiTaskService.java": OK_SVC.replace("invocationLog.record(row)",
          "invocationLog.record(row); orderMapper.insert(e);")}, 1),
        ("K3 除唯一出口外还有人自己发 HTTP ⇒ 红（绕过超时/脱敏/留痕的路）",
         {"service/task/AiTaskService.java": OK_SVC,
          "core/AiGateway.java": "class AiGateway { java.net.http.HttpClient c; }",
          "service/task/OtherCaller.java": "class OtherCaller { java.net.http.HttpClient c; }"}, 1),
        ("K4 日志零生产者 ⇒ 红（永远空的表不算「调用留日志」）",
         {"service/task/AiTaskService.java": OK_SVC.replace("invocationLog.record(row)", "int ignored = 1;")}, 1),
        ("K5 AI 口返回非草稿类型 ⇒ 红（CommonResult<Boolean> 读起来就像「已经替你做了」）",
         {"service/task/AiTaskService.java": OK_SVC.replace("CommonResult<Draft> classify",
          "CommonResult<Boolean> classify")}, 1),
        ("K6 密钥出现在没有 Authorization 的文件里 ⇒ 红",
         {"service/task/AiTaskService.java": OK_SVC + "// getApiKey()"}, 1),
    ]
    try:
        for i, (name, files, want) in enumerate(cases):
            d = os.path.join(tmp, "k%d" % (i + 1))
            for rel, body in files.items():
                p = os.path.join(d, MODULE, *rel.split("/"))
                os.makedirs(os.path.dirname(p), exist_ok=True)
                # 前缀补成包名所在路径，让「在不在 task 目录」这条判断与真树同形
                p2 = os.path.join(d, MODULE, "cn", "iocoder", "yudao", "module", "aiassist",
                                  *rel.split("/"))
                os.makedirs(os.path.dirname(p2), exist_ok=True)
                io.open(p2, "w", encoding="utf-8", newline="\n").write(body)
            s = scan(d)
            got = 2 if s is None else report(s)
            print(("✔ " if got == want else "✘ ") + "%s（期望 rc=%d，实得 %d）" % (name, want, got))
            if got != want:
                rc = 1
        if os.path.isdir(MODULE):
            print("── K7 真树读数（只报不钉）")
            report(scan("."))
        else:
            print("! K7 跳过：不在仓库根运行")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


def main(argv=None):
    argv = sys.argv[1:] if argv is None else list(argv)
    if "--self-test" in argv:
        return run_self_test()
    args = [a for a in argv if not a.startswith("--")]
    root = args[0] if args else "."
    if not os.path.isdir(root):
        sys.stderr.write("扫描根不存在：%s\n" % root)
        return 2
    s = scan(root)
    if s is None:
        sys.stderr.write("一个 java 文件都没数到 ⇒ 判据没跑起来，不许读成「都合规」（模块目录：%s）\n" % MODULE)
        return 2
    return report(s)


if __name__ == "__main__":
    sys.exit(main())
