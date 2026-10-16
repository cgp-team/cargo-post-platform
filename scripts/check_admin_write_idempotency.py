#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""后台写口的服务端幂等键（队列 #89 / 台账 D-136）。

病是什么：后台点一次「智能派单」就生成一个方案，点两次就是两个**互相竞争同一批订单**的方案；
`adjust-balance` 重复一次就是**两次真金白银**。今天挡着这类重复的只有前端：18 个写按钮有在飞锁、
5 个不可逆口有确认框 —— 那两个层都只在**同一个浏览器页签**里有效。两个会话、两块屏、
一个脚本、一次"请求发出去了但回执没收到"的手动重试，前端一句都挡不了。

口径（为什么只钉这 9 个，不给 49 个全覆盖）：
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
退出码：0 = 在册 9 口全部带合规幂等键且对照数吻合；1 = 有缺/形状不合/在册漂移；2 = 扫描根不存在或一个写口都没数到。
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
AI_ROOT = os.path.join("yudao-module-ai-assist", "src", "main", "java", "cn", "iocoder", "yudao",
                       "module", "aiassist", "controller", "admin")
# 名册：模块的 admin 根 → (写口现读数, 在册必须带键数)。**每个数都必须是实跑出来的**（估的不算数，见下）。
# 为什么要有这张名册（队列 #99 / 台账 D-141）：这本闸的扫描根以前只有 transport 一个，于是第二条线新建的
# `yudao-module-ai-assist` 那 5 个写口**不在任何闸的视野里** —— 她自己给 saved-query 的 create 钉了
# `@Idempotent`（形状是对的），但**那句被删掉也不会有人红**。"保护写在一处、绑定漏在另一处"是静默的，
# 与本仓 #75 那格（声明了 xxxLoading 却没接到按钮上）同一种病。
MODULE_ADMIN = {}
PIN_ADMIN_WRITES = 49          # 现读对照数：admin 写口总数，变了要同轮写清原因
# 45 → 46（2026-10-16，队列 #92）：新增 `PUT /transport/notification/mark-admin-read`
#   （把一条管理员告警标为已处理）。这一口**故意不钉幂等键**，按上面的口径：PUT 且重复=同结果，
#   而且服务层自己已经幂等（`markAsRead` 见已读直接 return，不再写库）。
# 46 → 49（同日，队列 #93）：新控制器 TownEventController 一口气带进**三个**写口
#   （POST create / PUT update / DELETE delete）。我先按 +1 写成 47，被这本闸当场打回（现读 49 ≠ 47）
#   —— 把这一笔留在这儿是因为它正是这本闸存在的意义：**估的不算数，扫面变了必须重新数**。
#   三个里只有 create 钉键（属于"重复一次多一条记录"那一类，且服务层的同镇同日查重在并发下会双双通过）；
#   update / delete 重复=同结果，按上面的口径不钉。
PIN_COVERED = 9                # 在册必须带幂等键的口数

MODULE_ADMIN[ADMIN_ROOT] = (PIN_ADMIN_WRITES, PIN_COVERED)
# ai-assist 的两个数都是这一轮实测的（`mvn -B -pl yudao-module-ai-assist -am test` BUILD SUCCESS、19 条用例；
# 写口用同一台提取器数）：POST /transport/ai/saved-query/create、PUT update、DELETE delete、
# POST /transport/ai/capacity-conflict/check、POST /transport/ai/briefing/render ⇒ 写口 5。
# 名册只钉 create 一个：check / render 是**只算不写**的口（读实现确认过：一个走约束校验返回冲突点，
# 一个把已聚合的数据排成简报），update / delete 重复=同结果 —— 按同一口径都不钉。
PIN_AI_WRITES, PIN_AI_COVERED = 5, 1
MODULE_ADMIN[AI_ROOT] = (PIN_AI_WRITES, PIN_AI_COVERED)
# 明确划成"不在本闸范围"的上游模块（现读：system 106 / infra 30 / member 16 个写口，0 个带键）。
# 为什么不管而不是"顺手一起钉"：这三个是 RuoYi-Vue-Pro 上游重新引入的业务面（用户/角色/文件/会员），
# 它们的写口语义不由我们定，一次升级就会变；把它们钉成对照数只会得到一台**永远红**的闸 ——
# 而"红到没人管"的闸等于没有闸（与本仓 #74/#75 只圈 transport 域是同一条口径）。
# 这本闸对它们的真正用处是上面那条"名册外且有写口"的红：**新出现的自研模块必须被分类**，
# 想蒙混进 UPSTREAM_MODULES 也得留一句为什么。
UPSTREAM_MODULES = {"yudao-module-system", "yudao-module-infra", "yudao-module-member"}

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
    "POST /transport/town-event/create": "日历重复登记 = 同一个集日被情景提示数两遍（服务层查重按 start_date，"
                                         "两次并发提交能同时通过）",
    # 队列 #99：这条理由是对着实现读出来的，不是照名字编的 —— createQuery 就是 `insert(entity)` 后回 id，
    # **没有任何同名校验/唯一约束**，所以双击 = 两条同名条件（台账 D-136 里那四条"编出来的理由"就是这么被抓的）。
    "POST /transport/ai/saved-query/create": "保存的查询条件重复提交 = 存下两条同名条件（createQuery 是裸 insert，无查重无唯一键）",
}
# 在册口属于哪个模块的扫描根。没有这张表，"模块还没落地"就会被判成"端点被删了"——
# 而那种红会让人去改一个根本还没提交的文件（本轮真的差点这么发生：只推闸、不推模块时 CI 会红）。
MUST_COVER_OWNER = {"POST /transport/ai/saved-query/create": AI_ROOT}


def read_lines(path):
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n").split("\n")


def scan(root, admin=None):
    """返回 (rows, bad_shape)：rows = [(key, idm|None, 文件, 行号)]，只含写口。

    解析法换过一次（这是这本第三次栽在"认形状"上，记在这儿）：
    早先按**注解块累加**，多行方法签名（`replanLeg(@RequestParam… Long legId,` 换行接参）会被当成注解续行，
    于是**上一个口的动词顶到下一个口上** —— 读数里出现过 `PUT …/bind`，而树上那是 `@PostMapping("/bind")`。
    现在只做一件笨事：从 @XMapping 那一行**往下**找到方法签名，中间这段就是注解区；
    行号与动词都取自我看见的那一行，不再靠邻近推断。"""
    rows, bad_shape = [], []
    base = os.path.join(root, admin or ADMIN_ROOT)
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


def judge(rows, demand=None):
    """按口径判每条在册口；返回（违规文本列表, 现读覆盖数）。

    `demand` 让 main 能在"某个名册模块这轮不在树里"时，把它名下的在册口**排除在本轮判决之外**
    —— 而不是把它们判成"端点被删了"。跳过必须大声打出来（见 main），不静默。
    """
    by_key = {}
    for key, idm, rel, lineno in rows:
        by_key.setdefault(key, []).append((idm, rel, lineno))
    out, covered = [], 0
    for key, why in sorted((demand if demand is not None else MUST_COVER).items()):
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


def module_deployed(root, mod):
    """这个模块挂没挂在 yudao-server 上（= 这台机器要不要部署它）。读 pom，不猜。"""
    p = os.path.join(root, "yudao-server", "pom.xml")
    if not os.path.isfile(p):
        return False
    text = io.open(p, encoding="utf-8", errors="replace").read()
    return ("<artifactId>%s</artifactId>" % mod) in text


def discover_admin_roots(root):
    """列出树里所有 `<module>/src/.../controller/admin` 目录：名册外的模块靠它抓出来。"""
    out = []
    if not os.path.isdir(root):
        return out
    for mod in sorted(os.listdir(root)):
        if not mod.startswith("yudao-module-"):
            continue
        base = os.path.join(root, mod)
        for dirpath, _dirnames, _names in os.walk(base):
            rel = os.path.relpath(dirpath, root)
            if rel.endswith(os.path.join("controller", "admin")):
                out.append((mod, rel))
    return out


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
    bad, rows_all, skipped = [], [], []
    for admin, roster in sorted(MODULE_ADMIN.items()):
        pin_w, pin_c = roster
        if admin == ADMIN_ROOT:
            # transport 这两个数**必须在这里重读全局**：七把自检刀改的就是 PIN_ADMIN_WRITES / PIN_COVERED
            # 这对全局（fixture 树里只有 adjust-balance 一个口）。MODULE_ADMIN 建的是导入时的快照，
            # 不重读的话 K1–K7 会红在"对照数不符"这一格，而不是各自该钉的那一格。
            pin_w, pin_c = PIN_ADMIN_WRITES, PIN_COVERED
        d = os.path.join(root, admin)
        mod = admin.split(os.sep)[0]
        if not os.path.isdir(d):
            # 名册里有、目录没有：**只有在这个模块已经挂到 yudao-server 上时才判红**。
            # 不这么分就两种坏法：要么"模块还没落地"永久红（红到没人管 = 没有闸），
            # 要么"模块被删了"被念成"少几个口而已"。挂载状态就是那台机器要不要部署它的真值。
            if module_deployed(root, mod):
                bad.append("✘ %s 挂在 yudao-server 的 pom 上，但扫描根找不到（%s）⇒ 部署面与扫描面不一致" % (mod, admin))
            else:
                print("  （%s 不在树里也没挂载 ⇒ 这一轮没有它的写口要判）" % mod)
                skipped.append(admin)
            continue
        rows, _shape = scan(root, admin)
        rows_all.extend(rows)
        keyed = len([1 for _k, idm, _f, _l in rows if idm is not None])
        print("[扫描] %s ⇒ 写口 %d 个（在册 %d）；自带合规幂等键 %d 个" % (mod, len(rows), pin_w, keyed))
        if len(rows) != pin_w:
            bad.append("✘ %s 对照数现读 %d ≠ 在册 %d：扫描面变了（新控制器/新写口/提取器坏了），"
                       "先把差额逐条数出来再改本文件顶部的数" % (mod, len(rows), pin_w))
        # ⚠ 每个模块只判**自己名册里那几道口**：第一版在这里对每个模块都跑了一遍全量 judge，
        #   于是 transport 的 9 条在册口被报成"ai-assist 里找不到"、ai-assist 那条被报成"transport 里找不到"
        #   —— 一串假红，而且红的内容与真正的洞毫无关系。在册表是按端点路径全局匹配的，只能判一次。
        mine = {k: v for k, v in MUST_COVER.items() if any(k == rr[0] for rr in rows)}
        want = PIN_COVERED if admin == ADMIN_ROOT else pin_c
        if len(mine) != want:
            bad.append("✘ %s 名册里应有 %d 个在册口，这一轮只对上 %d 个（在册表与这个模块的端点对不上，"
                       "要么口被改名/删了，要么名册加错了模块）" % (mod, want, len(mine)))
    # 名册外的模块：只要它有写口又没进名册，就是"保护没人管"的洞（这本闸自己的覆盖闸）
    for mod, admin in discover_admin_roots(root):
        if admin in MODULE_ADMIN or mod in UPSTREAM_MODULES:
            continue
        rows, _s = scan(root, admin)
        if rows:
            keyed = len([1 for _k, idm, _f, _l in rows if idm is not None])
            bad.append("✘ %s 有 %d 个写口（其中 %d 个自带幂等键）既不在名册、也没被明确划成上游 ⇒ "
                       "这些口的重复执行没有任何闸在管；要么加进 MODULE_ADMIN（同轮写两个实测数），"
                       "要么把它归进 UPSTREAM_MODULES 并写清为什么不管"
                       % (mod, len(rows), keyed))
    if not rows_all:
        print("✘ 一个写口都没数到 ⇒ 判据没跑起来，不许读成「都合规」")
        return 2
    demand = {k: v for k, v in MUST_COVER.items()
              if MUST_COVER_OWNER.get(k) not in skipped}
    if len(demand) != len(MUST_COVER):
        print("  （本轮因模块未落地而不判的在册口 %d 条：%s；模块一进树就恢复被判）"
              % (len(MUST_COVER) - len(demand),
                 "、".join(sorted(set(MUST_COVER) - set(demand)))))
    bad_shape, covered = judge(rows_all, demand)
    bad = bad_shape + bad
    print("[汇总] 全仓 admin 写口 %d 个；带合规幂等键 %d 个（本轮在册应判 %d / 名册共 %d）"
          % (len(rows_all), covered, len(demand), len(MUST_COVER)))
    extra = sorted({k for k, idm, _r, _l in rows_all if idm is not None} - set(MUST_COVER))
    if extra:
        print("  （另有 %d 个口自带幂等键、不在册，登记供复核：%s）" % (len(extra), "、".join(extra)))
    for b_ in bad:
        print(b_)
    if bad:
        return 1
    print("✅ 在册 %d 个后台写口都带 UserIdempotentKeyResolver 的幂等键，键窗 10–300 秒，msg 指名后果"
          % len(demand))
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
                print("✔ K0 真树合规（在册 9 口全带键）")
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
        # K8 名册外的新模块有写口 ⇒ 必须红（队列 #99 的成因就是这一格以前没人判：
        #    ai-assist 落地时这本闸只看得到 transport，那 5 个口完全在视野外）
        d8 = os.path.join(tmp, "k8")
        leaf8 = os.path.join(d8, ADMIN_ROOT)
        os.makedirs(leaf8, exist_ok=True)
        with io.open(os.path.join(leaf8, "WalletController.java"), "w", encoding="utf-8", newline="\n") as f:
            f.write(FX_OK)
        new_leaf = os.path.join(d8, "yudao-module-newbiz", "src", "main", "java", "cn", "iocoder", "yudao",
                                "module", "newbiz", "controller", "admin")
        os.makedirs(new_leaf, exist_ok=True)
        with io.open(os.path.join(new_leaf, "NewBizController.java"), "w", encoding="utf-8", newline="\n") as f:
            f.write(FX_NO_IDEM.replace("/transport/wallet", "/transport/newbiz"))
        PIN_ADMIN_WRITES, PIN_COVERED = 1, 1
        got8 = _run_quiet(d8)
        out8 = _run_capture(d8)
        if got8 != 1 or "不在名册" not in out8:
            print("✘ K8 名册外模块有写口却 rc=%d（期望 1，且要点名「不在名册」）；读数：%s"
                  % (got8, " ".join(out8.split())[-90:]))
            rc = 1
        else:
            print("✔ K8 新模块有写口却没进名册 ⇒ 红并点名（这本闸自己的覆盖闸）")
        # K9 名册里挂着、目录被删，但 pom 还在部署它 ⇒ 必须红（部署面与扫描面不一致）
        d9 = os.path.join(tmp, "k9")
        os.makedirs(os.path.join(d9, ADMIN_ROOT), exist_ok=True)
        with io.open(os.path.join(d9, ADMIN_ROOT, "WalletController.java"), "w", encoding="utf-8", newline="\n") as f:
            f.write(FX_OK)
        os.makedirs(os.path.join(d9, "yudao-server"), exist_ok=True)
        with io.open(os.path.join(d9, "yudao-server", "pom.xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write("<project><dependencies><dependency>"
                    "<artifactId>%s</artifactId></dependency></dependencies></project>\n"
                    % AI_ROOT.split(os.sep)[0])
        got9 = _run_quiet(d9)
        if got9 != 1:
            print("✘ K9 pom 挂着 ai-assist 而目录不存在却 rc=%d（期望 1）" % got9)
            rc = 1
        else:
            print("✔ K9 已挂载但扫描根不见了 ⇒ 红（不许把模块被删念成少几个口）")
    finally:
        MUST_COVER.clear()
        MUST_COVER.update(orig_must)
        shutil.rmtree(tmp, ignore_errors=True)
    return rc


def _run_capture(root):
    """把 main 打出来的字取回来（K8 那把要验"红得对不对地方"，光看 rc 不够 ——
    rc=1 可能来自任何一条别的洞，那这一把就钉不住它声称的那一格）。"""
    import contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        main([root])
    return buf.getvalue()


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
