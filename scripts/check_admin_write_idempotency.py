#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后台写口的服务端幂等键（队列 #89 / 台账 D-136）。

病是什么：后台点一次「智能派单」就生成一个方案，点两次就是两个**互相竞争同一批订单**的方案；
`adjust-balance` 重复一次就是**两次真金白银**。今天挡着这类重复的只有前端：18 个写按钮有在飞锁、
5 个不可逆口有确认框 —— 那两个层都只在**同一个浏览器页签**里有效。两个会话、两块屏、
一个脚本、一次"请求发出去了但回执没收到"的手动重试，前端一句都挡不了。

口径（为什么只钉这 8 个，不给 46 个全覆盖）：
· 判据只有一条：**重复执行会不会多出第二份东西**。逐个读过实现才定，不看名字猜。
· 字典类 create（站点/车辆/司机/线路/班次/商品/公告）**故意不钉**：运营连着建两条同名记录是有意的，
  10 秒的键会把正当操作吞掉。
· `order-pool/collect` 不钉：按 `status=8` 过滤，第二次自然 0 行。
· PUT/DELETE 语义上重复=同结果，也不钉。
· 有 4 个口第一版被我收进册子、读了实现才发现**写的是假话**，已撤（撤的是假理由，不是放宽判据）：
  - `driver-vehicle/bind`：`DriverVehicleServiceImpl.bind` 先 `unbindAll(按司机)` 再 `unbindAll(按车辆)`
    才插入 ⇒ 同参数重复的净效果还是"一对人车"，不是我以为的"两条生效中的绑定"。
  - `dispatch/plan/review`：只允许 `PENDING` 审核，第二次被 `DISPATCH_PLAN_STATUS_ILLEGAL` 拒 ⇒ 状态机已挡。
  - `order/allocate`：`allocateDynamic` → `AlgorithmAdapter.allocateRaw` → `algorithmClient.allocate`，
    **整条没有落库**，它只是问算法要一个结果给界面看 ⇒ 不存在"生成第二个方案"。
  - `feedback/reply`：一个 `updateById` 覆盖 `status/reply/replyTime` ⇒ 同参数重复=同一个值。

形状坑（这本自己撞过的两条，写在这儿是为了下一轮别再撞）：
· 注解是多行的：`@Idempotent(timeout = 10, ... ,\n        message = "...")` ⇒ 只往上数 `@` 开头的行会把
  带注解的口读成 0（那是空转闸门的成因）。这里按**注解块**归属：连续注解 + 方法签名算一块。
· 按行号邻近配对会把上一个方法的注解顶给下一个方法（假阳性）。同一个原因。

用法：python3 scripts/check_admin_write_idempotency.py [--self-test] [仓库根]
退出码：0 = 在册 8 口全部带合规幂等键且对照数吻合；1 = 有缺/形状不合/在册漂移；2 = 扫描根不存在或一个写口都没数到。
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

ADMIN_ROOT = os.path.join("yudao-module-transport", "src", "main", "java", "cn", "iocoder", "yudao",
                          "module", "transport", "controller", "admin")
PIN_ADMIN_WRITES = 46          # 现读对照数：admin 写口总数，变了要同轮写清原因
# 45 → 46（2026-10-16，队列 #92）：新增 `PUT /transport/notification/mark-admin-read`
#   （把一条管理员告警标为已处理）。这一口**故意不钉幂等键**，按上面的口径：PUT 且重复=同结果，
#   而且服务层自己已经幂等（`markAsRead` 见已读直接 return，不再写库）。
PIN_COVERED = 8                # 在册必须带幂等键的口数

MAP_RE = re.compile(r"@(Post|Put|Delete|Patch)Mapping\b(?:\s*\(\s*(?:value\s*=\s*)?\"([^\"]*)\")?")
CLS_RE = re.compile(r"@RequestMapping\(\s*\"([^\"]+)\"")
SIGN_RE = re.compile(r"^\s*(?:public|protected|private)\b.*\(")
IDEM_RE = re.compile(r"@Idempotent\b")
TIMEOUT_RE = re.compile(r"timeout\s*=\s*(\d+)")
RESOLVER_RE = re.compile(r"keyResolver\s*=\s*(\w+)\.class")
MSG_RE = re.compile(r"message\s*=\s*\"([^\"]*)\"")

# 在册表：键 = "METHOD 路径"，值 = 为什么重复执行有害（这句会打进读数，不接受空着）
MUST_COVER = {
    "POST /transport/dispatch/plan/manual": "手工派单重复提交 = 同一批订单被派两次（每次都 insert 一条方案）",
    "POST /transport/dispatch/plan/smart": "智能派单重复提交 = 两个方案 + 两轮算法",
    "POST /transport/dispatch/plan/prefetch-road": "预热重复跑 = 重复消耗高德配额（无状态守卫）",
    "POST /transport/dispatch/departure-check": "发车核验每次都 departureCheckMapper.insert ⇒ 重复一次多一条核验记录",
    "POST /transport/notification/send": "通知重复提交 = 给用户多发一条，且没有撤回口",
    "POST /transport/order/create": "运单重复创建 = 两张真单，后面每一环都会各跑一遍",
    "POST /transport/topology/replan-leg": "重调度每次都 updateById 换车换司机（legMapper/planMapper 各一处，实读）",
    "POST /transport/wallet/adjust-balance": "调账重复执行 = 同一笔钱调两次（无任何状态守卫，直接 recharge/consume）",
}


def read_lines(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n").split("\n")


def scan(root):
    """返回 (rows, bad_shape)：rows = [(key, idm|None, 文件, 行号)]，只含写口。

    解析法换过一次（这是这本第三次栽在"认形状"上，记在这儿）：
    早先按**注解块累加**，多行方法签名（`replanLeg(@RequestParam… Long legId,` 换行接参）会被当成注解续行，
    于是**上一个口的动词顶到下一个口上** —— 读数里出现过 `PUT …/bind`，而树上那是 `@PostMapping("/bind")`。
    现在只做一件笨事：从 @XMapping 那一行**往下**找到方法签名，中间这段就是注解区；
    行号与动词都取自我看见的那一行，不再靠邻近推断。"""
    rows, bad_shape = [], []
    base = os.path.join(root, ADMIN_ROOT)
    for dirpath, _d, names in os.walk(base):
        for name in sorted(names):
            if not name.endswith("Controller.java"):
                continue
            p = os.path.join(dirpath, name)
            lines = read_lines(p)
            cls = ""
            for ln in lines[:80]:
                m = CLS_RE.search(ln)
                if m:
                    cls = m.group(1)
                    break
            for i, ln in enumerate(lines, start=1):
                m = MAP_RE.search(ln)
                if not m:
                    continue
                if SIGN_RE.match(ln.lstrip("@")):
                    continue
                http = m.group(1).upper()
                path = m.group(2) or ""
                key = "%s %s" % (http, (cls + path) or path)
                # 往下走到方法签名为止，这段就是该口的注解区
                ann = []
                j = i
                while j < len(lines) and j < i + 14:
                    cur = lines[j]
                    if j > i and SIGN_RE.match(cur):
                        break
                    if j > i and MAP_RE.search(cur):
                        break          # 撞到下一个口：上面那段没签名，形状不合，交给 bad_shape
                    ann.append(cur.strip())
                    j += 1
                else:
                    bad_shape.append("%s:%d 注解区没等到方法签名" % (p, i))
                joined = "\n".join(ann)
                idm = None
                if IDEM_RE.search(joined):
                    seg = joined[joined.index("@Idempotent"):][:600]
                    t = TIMEOUT_RE.search(seg)
                    r = RESOLVER_RE.search(seg)
                    g = MSG_RE.search(seg)
                    idm = (r.group(1) if r else "", int(t.group(1)) if t else -1, g.group(1) if g else "")
                rows.append((key, idm, os.path.relpath(p, root).replace(os.sep, "/"), i))
    return rows, bad_shape


def judge(rows):
    """按口径判每条在册口；返回（违规文本列表, 现读覆盖数）。"""
    by_key = {}
    for key, idm, rel, lineno in rows:
        by_key.setdefault(key, []).append((idm, rel, lineno))
    out, covered = [], 0
    for key, why in sorted(MUST_COVER.items()):
        hits = by_key.get(key)
        if not hits:
            out.append("✘ 在册口在树上找不到：%s（端点被改名/删掉了，同轮改本文件顶部的在册表）" % key)
            continue
        if len(hits) > 1:
            out.append("✘ %s 命中 %d 处：一个路径两个方法，幂等键按方法名算，先分清哪个才是写口" % (key, len(hits)))
        for idm, rel, lineno in hits:
            if idm is None:
                out.append("✘ %s 没有 @Idempotent（%s:%d）—— 危害：%s" % (key, rel, lineno, why))
                continue
            resolver, timeout, message = idm
            if resolver != "UserIdempotentKeyResolver":
                out.append("✘ %s 的 resolver 是 %r（%s:%d）—— Default 按 IP+URI 算键，会把不同运营的正当二次操作吞进同一个键"
                           % (key, resolver, rel, lineno))
            if not (10 <= timeout <= 300):
                out.append("✘ %s 的 timeout=%d 越界（%s:%d）—— <10 挡不住双击，>300 会把正常的连续操作也吞掉"
                           % (key, timeout, rel, lineno))
            if "重复提交" not in message:
                out.append("✘ %s 的 message 没指名后果：%r（%s:%d）—— 运营读到的必须是「这一笔还在处理中」，不是「请求太频繁」"
                           % (key, message, rel, lineno))
            covered += 1
    return out, covered


def main(argv):
    self_test = "--self-test" in argv
    args = [a for a in argv if a != "--self-test"]
    root = args[0] if args else "."
    if self_test:
        return run_self_test()
    base = os.path.join(root, ADMIN_ROOT)
    if not os.path.isdir(base):
        print("✘ 扫描根不存在：%s（不在仓库根跑要显式给路径）" % base)
        return 2
    rows, _ = scan(root)
    admin_writes = len(rows)
    if admin_writes == 0:
        print("✘ 一个写口都没数到 ⇒ 判据没跑起来，不许读成「都合规」")
        return 2
    bad, covered = judge(rows)
    print("[扫描] admin 写口 %d 个（在册 %d）；带合规幂等键 %d 个" % (admin_writes, PIN_ADMIN_WRITES, covered))
    if admin_writes != PIN_ADMIN_WRITES:
        bad.append("✘ 对照数现读 %d ≠ 在册 %d：扫描面变了（新控制器/新写口/提取器坏了），"
                   "先把差额逐条数出来再改本文件顶部的数" % (admin_writes, PIN_ADMIN_WRITES))
    if covered != PIN_COVERED:
        bad.append("✘ 带合规幂等键的在册口 %d ≠ %d" % (covered, PIN_COVERED))
    extra = sorted({k for k, idm, _r, _l in rows if idm is not None} - set(MUST_COVER))
    if extra:
        print("  （另有 %d 个口自带幂等键、不在册，登记供复核：%s）" % (len(extra), "、".join(extra)))
    for b in bad:
        print(b)
    if bad:
        return 1
    print("✅ 在册 %d 个后台写口都带 UserIdempotentKeyResolver 的幂等键，键窗 10–300 秒，msg 指名后果"
          % PIN_COVERED)
    return 0


# ---------------- 自检：一条刀一个目录（同一目录放多条 fixture 会让读数互相顶包） ----------------

FX_OK = '''package x;
import cn.iocoder.yudao.framework.idempotent.core.annotation.Idempotent;
@RestController
@RequestMapping("/transport/wallet")
public class WalletController {
    @PostMapping("/adjust-balance")
    @PreAuthorize("@ss.hasPermission('a')")
    @Idempotent(timeout = 10, timeUnit = TimeUnit.SECONDS, keyResolver = UserIdempotentKeyResolver.class,
            message = "这笔余额调整正在处理中，请勿重复提交（重复提交会调两次钱）")
    public CommonResult<String> adjustBalance(@RequestParam("userId") Long userId) {
        return null;
    }
}
'''
FX_NO_IDEM = FX_OK.replace('''    @Idempotent(timeout = 10, timeUnit = TimeUnit.SECONDS, keyResolver = UserIdempotentKeyResolver.class,
            message = "这笔余额调整正在处理中，请勿重复提交（重复提交会调两次钱）")
''', "")
FX_MULTILINE_ONLY = FX_OK  # 合规样例本身就是两行注解：它被数到 = 多行形状解析成立
FX_BAD_RESOLVER = FX_OK.replace("UserIdempotentKeyResolver.class", "DefaultIdempotentKeyResolver.class")
FX_BAD_TIMEOUT = FX_OK.replace("timeout = 10", "timeout = 5")
FX_BAD_MSG = FX_OK.replace("message = \"这笔余额调整正在处理中，请勿重复提交（重复提交会调两次钱）\"",
                           "message = \"请求过于频繁\"")


def run_self_test():
    import shutil
    import tempfile
    global ADMIN_ROOT
    rc = 0
    tmp = tempfile.mkdtemp(prefix="idem-")
    orig_must = dict(MUST_COVER)
    try:
        # K0 真树：**必须趁在册表与钉值还是原样时跑**（后面每一把刀都会临时换掉它们）。
        #      这条早先放在最后，于是它拿的是 fixture 的单口册表 + 自己写死的 11，
        #      真树明明绿却被判红 —— 自检把自家的号也对不上，正是这本要防的那类事。
        if os.path.isdir(ADMIN_ROOT):
            got0 = _run_quiet(".")
            if got0 != 0:
                print("✘ K0 真树 rc=%d（期望 0）" % got0)
                rc = 1
            else:
                print("✔ K0 真树合规（在册 8 口全带键）")
        else:
            print("! K0 跳过：不在仓库根运行")
        MUST_COVER.clear()
        MUST_COVER["POST /transport/wallet/adjust-balance"] = "调账重复执行 = 同一笔钱调两次"
        cases = [
            ("K1 多行 @Idempotent 要被数到（合规形状本身）", FX_MULTILINE_ONLY, 0),
            ("K2 注解被删掉必须红", FX_NO_IDEM, 1),
            ("K3 resolver 换成 Default 必须红", FX_BAD_RESOLVER, 1),
            ("K4 timeout 掉到 5 秒必须红", FX_BAD_TIMEOUT, 1),
            ("K5 message 退回通用文案必须红", FX_BAD_MSG, 1),
        ]
        for i, (name, body, want) in enumerate(cases):
            d = os.path.join(tmp, "k%d" % (i + 1))
            leaf = os.path.join(d, ADMIN_ROOT)
            os.makedirs(leaf, exist_ok=True)
            with io.open(os.path.join(leaf, "WalletController.java"), "w", encoding="utf-8", newline="\n") as f:
                f.write(body)
            global PIN_ADMIN_WRITES, PIN_COVERED
            PIN_ADMIN_WRITES, PIN_COVERED = 1, (1 if want == 0 else 0)
            got = _run_quiet(d)
            if got != want:
                print("✘ %s：期望 rc=%d，实得 %d" % (name, want, got))
                rc = 1
            else:
                print("✔ %s（rc=%d）" % (name, got))
        # K6 扫描面里没有写口 ⇒ 必须 rc=2（不许读成"全都干净"）
        d6 = os.path.join(tmp, "k6")
        os.makedirs(os.path.join(d6, ADMIN_ROOT), exist_ok=True)
        with io.open(os.path.join(d6, ADMIN_ROOT, "ReadonlyController.java"), "w", encoding="utf-8", newline="\n") as f:
            f.write("package x;\n@RestController\n@RequestMapping(\"/transport/x\")\n"
                    "public class ReadonlyController {\n    @GetMapping(\"/page\")\n"
                    "    public String page() { return null; }\n}\n")
        got6 = _run_quiet(d6)
        if got6 != 2:
            print("✘ K6 一个写口都没有时 rc=%d（期望 2：判据空转不许读成绿）" % got6)
            rc = 1
        else:
            print("✔ K6 无候选 ⇒ rc=2 出口（对照数 0 时不报绿）")
        # K7 在册表里写了树上不存在的口 ⇒ 必须红（册子不许停在过去）
        d7 = os.path.join(tmp, "k7")
        leaf7 = os.path.join(d7, ADMIN_ROOT)
        os.makedirs(leaf7, exist_ok=True)
        with io.open(os.path.join(leaf7, "WalletController.java"), "w", encoding="utf-8", newline="\n") as f:
            f.write(FX_OK.replace("/adjust-balance", "/adjust-balance-v2"))
        PIN_ADMIN_WRITES, PIN_COVERED = 1, 0
        got7 = _run_quiet(d7)
        if got7 != 1:
            print("✘ K7 在册端点改了路径却 rc=%d（期望 1）" % got7)
            rc = 1
        else:
            print("✔ K7 在册口找不到即红（防册子停留在过去）")
    finally:
        MUST_COVER.clear()
        MUST_COVER.update(orig_must)
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


def _run_quiet(root):
    """把 main 的退出码取出来，同时不刷屏（自检只关心红/绿与刀是否咬住）。"""
    import contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = main([root])
    last = [l for l in buf.getvalue().split("\n") if l.strip()]
    if code != 0:
        for l in last:
            if l.startswith("✘"):
                print("    刀口读回：%s" % l[:110])
                break
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
