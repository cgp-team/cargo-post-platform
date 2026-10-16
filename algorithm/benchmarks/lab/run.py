"""度量台运行器：多臂求解 → 独立校验 → 指标聚合 → 版本台账。

臂（arm）：
  haco        生产默认预算（haco_time_limit=4s），每实例多随机种子
  baseline    出厂 OR-Tools 确定性解（参照之一）
  long        HACO 5 倍预算（自身天花板：1 倍预算离天花板多远 = 欠搜索程度）
参照解 BKS = 上述所有臂里 lexicographic 最优。诚实口径：这是 gap-to-best-known，
不是「距最优」——本问题没有可辩护的精确最优 oracle（exact_oracle.py 自己的
docstring 就禁止用它做最优性声明）。

判据口径：
  - 只有「独立校验通过」的方案才参与 gap 统计；违规方案记 invalid。
  - reject 样本 = 可证明「不可能全量服务」：要么拒判无解且原因正当，
    要么交方案但方案合规**且**如实申报了未服务订单；
    交出「全量已服务」的方案仍算错（防「乱塞」）。
  - serve 样本要求每个随机种子都出合规方案（鲁棒性）；
    覆盖率单独计（mean_coverage / min_coverage）—— 货运按政策可以舍弃，
    但必须进 unassignedOrderIds，悄悄丢单会被校验器的覆盖性规则判违规。

运行：
  cd algorithm && PYTHONPATH=. python -m benchmarks.lab.run --tag v0 --splits train,val --seeds 2 --jobs 6
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import time

# 必须在任何 numpy/openblas 被导入前设置：本机只有 16G 内存，
# 多 worker 各自拉起多线程 BLAS 会直接 OOM（OpenBLAS: Memory allocation failed）。
# 同时把并发钉成常数：HACO 预算按墙钟计时，负载抖动会污染版本间可比性。
for _v in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS",
           "NUMEXPR_NUM_THREADS", "VECLIB_MAXIMUM_THREADS"):
    os.environ.setdefault(_v, "1")

from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timezone

from app.models import AlgorithmConfig, AlgorithmMode
from app.solver import solve

from .check import check_plan
from .instances import Instance, split
from . import instances as synth
from . import real_corpus as real


def _load(iid: str):
    """按实例 id 前缀分派语料来源：r_* 走真实路网，online-* 走现网留痕，其余走合成几何。

    online 必须单独一支：`synth.load()` 用 `km_matrix()` 重建矩阵（直线口径），
    线上题要的恰恰是"真实路段 OD 下的现网请求"—— 走错支等于把 #35 记的分叉自己再造一遍。
    """
    if iid.startswith("r_"):
        return real.load(iid)
    if iid.startswith("online-"):
        from . import online_corpus
        return online_corpus.load_by_id(iid)
    return synth.load(iid)

LEDGER_DIR = os.path.join(os.path.dirname(__file__), "ledger")
LONG_FACTOR = 5
DEFAULT_JOBS = 4
ARM_ORDER = ("haco", "short", "baseline", "long", "softfree")
# 固定「工作量」而不是固定「墙钟」：实测同一实例同一随机种子在 4s 墙钟预算下
# 能跑出 12.99 / 13.56 / 13.56 三种里程（机器负载直接进结果里），
# 换成评估次数预算后连跑 4 次都是 10.987 —— 版本之间比的才是算法本身。
# 25,000 次评估 ≈ 生产 4s 预算的工作量（标定见 /tmp/calib.txt：约 6.3k eval/s）。
# 墙钟仍在台账里，但它的含义变成「每份搜索工作多贵」= 效率指标。
ARM_EVALS = {"haco": 25_000, "short": 6_250, "long": 125_000, "softfree": 25_000,
             "baseline": None}
# 只当保险丝，正常都由评估次数先收手。可用 LAB_ARM_TIME_LIMIT 抬高：
# 时效落地后重实例是保险丝先响（见 docs §9.7），此时「5 倍预算」这种说法
# 在墙上并不成立，anytime 单调性（任务 #6）就没法测 —— 抬高保险丝让评估次数
# 重新成为约束条件才能比。默认值不变，跨轮可比性不受影响。
ARM_TIME_LIMIT = float(os.environ.get("LAB_ARM_TIME_LIMIT", "120.0"))
# anytime 判据（aggregate 里的 service_key 对照）的里程噪声下限：5 m。
# 台账的 dist 已按 3 位小数四舍五入，同一条路线在两个臂里因浮点累加顺序不同
# 会差出 0.001~0.004 km —— 那不是"路变差了"，把它计成违背会把尺子读成缺陷清单。
# 只作用于里程维；丢单数与用车数是整数，不受这个容差影响。
ANYTIME_NOISE_KM = 0.005
# 臂 → 请求显式绕行阈值（km）。softfree 把阈值推到不可能触碰，
# 得到「不做最近站交接」的反事实臂，与 haco 同实例同种子配对比较，
# 这样软硬约束的净效果可以单独归因，不必回滚代码再跑一轮。
ARM_DETOUR_KM = {"softfree": 999.0}
REJECT_OK = {"OVER_CAPACITY", "TIMING_CONFLICT", "TIME_WINDOW_EXCEEDED", "PRELOAD_INSUFFICIENT"}
# 与 check.py / v14_solver 同一族：凡「没上门、改成站点交接」都算一次改站
HANDOVER_REASONS = {"DETOUR_OVER_THRESHOLD", "ROAD_NOT_BUS_ACCESSIBLE",
                    "NEAR_STATION_WITHIN_100M"}


def _evaluations(out) -> int | None:
    """求解器报出的实际评估次数；与 EVALUATION_CAP 对照才知道
    这一行到底是「评估次数上限先响」还是「墙钟保险丝先响」（口径可比性的关键）。"""
    for w in (getattr(out, "warnings", None) or []):
        if w.startswith("EVALUATIONS="):
            try:
                return int(w.split("=", 1)[1])
            except ValueError:
                return None
    return None


def _fallback_flag(out) -> str:
    """从遥测里取出兜底路径标记（NONE / SEED_RESCUE / DEFERRED / RESCUE_INCOMPLETE）。"""
    ws = getattr(out, "warnings", None) or []
    for w in ws:
        if w.startswith("SEED_RESCUE_INCOMPLETE"):
            return "RESCUE_INCOMPLETE"
        if w.startswith("DEFERRED_FOR_OUTPUT"):
            return "DEFERRED"
        if w.startswith("SEED_RESCUE_FULL_BUDGET"):
            return "SEED_RESCUE"
    return "NONE"


def _status_tag(r: dict) -> str:
    """台账里一行该怎么显示。

    异常行（`run_one` 里 solve 抛出去的那种）根本没有 `reason` 键 ——
    旧写法用 `r['reason']` 直接 KeyError，于是"一道题崩了"会升级成"整轮聚合崩了"，
    把唯一的现场证据也弄丢（线上留痕首轮实测踩到：`KeyError: 'reason'`）。
    崩掉必须显示成读数，不是显示成尺子自己的 traceback。
    """
    if r.get("status") == "error":
        return f"error/{r.get('error') or 'UNKNOWN'}"
    return f"{r.get('status')}/{r.get('reason')}"


def _cfg(mode: str, seed: int, evals: int | None = None,
         max_detour_km: float | None = None) -> AlgorithmConfig:
    kw: dict = {"algorithmMode": AlgorithmMode(mode), "randomSeed": seed}
    if mode == "HACO":
        kw["haco_time_limit"] = ARM_TIME_LIMIT
        kw["overall_time_limit"] = ARM_TIME_LIMIT
        kw["greedy_seed_time_limit"] = ARM_TIME_LIMIT / 5
        kw["max_evaluations"] = evals
    if max_detour_km is not None:
        kw["maxDetourDistanceKm"] = max_detour_km
    return AlgorithmConfig(**kw)


def run_one(iid: str, arm: str, seed: int) -> dict:
    inst = _load(iid)
    if inst is None:
        return {"instance_id": iid, "arm": arm, "seed": seed, "status": "error",
                "error": "INSTANCE_NOT_CACHED"}
    mode = "BASELINE" if arm == "baseline" else "HACO"
    req = inst.request.model_copy(update={
        "algorithmConfig": _cfg(mode, seed, ARM_EVALS.get(arm), ARM_DETOUR_KM.get(arm))})
    t0 = time.perf_counter()
    try:
        out = solve(req, inst.matrix)
    except Exception as exc:  # noqa: BLE001
        return {"instance_id": iid, "arm": arm, "seed": seed, "status": "error",
                "error": f"{type(exc).__name__}:{exc}",
                "wall_ms": round((time.perf_counter() - t0) * 1000, 1)}
    wall = round((time.perf_counter() - t0) * 1000, 1)
    plans = out.vehicle_plans or []
    errs = check_plan(req, inst.matrix, out) if out.status == "feasible" else []
    cargo = [s for p in plans for s in p.stops
             if s.action.value in ("PICKUP", "DELIVER")]
    # 客户真实请求点：orderId(+腿) → 站点。serviceMode/servicePoint 是项目既定约定
    # （servicePoint 就等于停靠站本身），所以步行成本要从订单侧反查。
    requested: dict[tuple[str, str], str] = {}
    for o in inst.request.orders:
        if o.orderType.value in ("DELIVERY", "PICKUP") and o.stationId:
            requested[(o.orderId, o.orderType.value)] = o.stationId
        if o.orderType.value == "PASSENGER":
            requested[(o.orderId, "BOARD")] = o.boardingStationId or ""
            requested[(o.orderId, "ALIGHT")] = o.alightingStationId or ""
    for sh in inst.request.shipments:
        requested[(sh.shipmentId, "PICKUP")] = sh.pickupStationId
        requested[(sh.shipmentId, "DELIVER")] = sh.deliveryStationId

    # 交接给乘客/货主带来的步行成本：请求点 → 实际交接站 的真实路段距离。
    # 没有这一项，「最近站交接」会表现为里程下降 —— 但那部分是转移给了用户，不是消失了。
    walk_km = 0.0
    relocations = 0
    for s in cargo:
        if s.reasonCode not in HANDOVER_REASONS:
            continue   # 交接原因码有三个（超阈值 / 公交进不去 / 站点就在旁边）：
                       # 只认第一个会把合规改写统计成「没发生」，步行成本也跟着漏计
        relocations += 1
        want = requested.get((s.orderId or "", s.action.value))
        if want and want != s.stationId:
            pair = inst.matrix.get((want, s.stationId))
            if pair:
                walk_km += pair[0]
    return {
        "instance_id": iid, "arm": arm, "seed": seed,
        "status": out.status, "reason": out.reason_code,
        "valid": not errs, "errors": errs[:8],
        "veh": sum(1 for p in plans if len(p.stops) > 1),
        "dist": round(out.total_distance, 3),
        "unassigned": len(out.unassigned_order_ids or []),
        "evaluations": _evaluations(out),
        # 求解器自己的业务字典序键（见 SolveOutcome.objective_key）。
        # JSON 落台账用 list；老台账没这个字段 → service_key 自动退回 (unassigned, veh, dist)。
        "objective_key": list(getattr(out, "objective_key", ()) or ()),
        # 兜底路径有没有走过：救援（可行但少 refinement）与暂摘（少服务）都必须可计数，
        # 否则「用尽预算换可行率」到底付了多少里程没人看得出来。
        "fallback": _fallback_flag(out),
        "cargo_stops": len(cargo),
        "relocations": relocations,
        "walk_km": round(walk_km, 3),
        "max_detour": (round(max((s.detourDistance or 0.0) for s in cargo), 3)
                       if cargo else 0.0),
        "wall_ms": wall,
        # 完整迭代代数：计数口径的读者。=0 且 feasible 意味着「交付的其实是贪心种子」
        # （搜索一代都没跑完），这正是阶段配额要治的病 —— 所以这一格必须有断言在读，
        # 不能只活在 ITERATION_COUNT 那条 warning 字符串里。
        "iterations": len(getattr(out, "iteration_stats", ()) or ()),
        "version": out.algorithm_version,
    }


def _remote(t):
    return run_one(*t)


def _collect(iids: list[str], arms: tuple[str, ...], seeds: list[int], jobs: int) -> list[dict]:
    # long 臂只求「天花板」，不需要多 seed：第 2 个种子起跳过，省一半高预算开销
    tasks = [(iid, arm, sd) for iid in iids for arm in arms
             for k, sd in enumerate(seeds) if not (arm == "long" and k > 0)]
    if jobs <= 1:
        return [run_one(*t) for t in tasks]
    with ProcessPoolExecutor(max_workers=jobs) as pool:
        return list(pool.map(_remote, tasks, chunksize=1))


def solution_key_from(row: dict) -> tuple:
    """臂结果 → 业务排序键（用车数 → 里程）。无效/无解排最后。"""
    if row.get("status") != "feasible" or not row.get("valid"):
        return (9_999, 9_999_999.0)
    return (row["veh"], row["dist"])


_SERVICE_DIM_NAMES = ("unassigned", "vehicle", "passenger_impact", "cargo_detour", "distance")
_SERVICE_DIM_NAMES_SHORT = ("unassigned", "vehicle", "distance")


def service_key_parts(row: dict) -> tuple[tuple, tuple[str, ...]]:
    """anytime 判据用的键，以及这个键每一位叫什么。

    优先用求解器随 SolveOutcome 导出的完整业务键（ObjectiveVector.key()），因为它含
    passenger_impact / cargo_detour 两维，而这两维**排在里程之前**：只看 (veh, dist)
    会把「少绕行换多一点里程」读成违背 —— 实测 r_vrp-L-005 两臂 un=0、veh=3 相同，
    long 的 max_detour 2.893→1.304、里程 +0.874km，按业务键是改善，按旧键却报违背。
    导出键第 0 维（infeasibility）不用：覆盖性以台账的 `unassigned`（真被舍弃的订单数）
    为准，那才是业务第一维。老台账没这个字段 → 退回 (unassigned, veh, dist) 并跟着换维度名。
    """
    shed = int(row.get("unassigned") or 0)
    exported = row.get("objective_key") or ()
    if len(exported) >= 5:
        return ((shed,) + tuple(exported[1:5])), _SERVICE_DIM_NAMES
    return (shed, row["veh"], round(float(row["dist"]), 6)), _SERVICE_DIM_NAMES_SHORT


def service_key(row: dict) -> tuple:
    """业务字典序键：服务量 → 用车数 → (乘客影响/货运绕行) → 里程。

    为什么不能复用 solution_key_from：它不含 unassigned，会把「多舍一单换短 10km」
    读成改善。业务键第一维是不可服务性（规格 §二.5），所以预算单调性必须按这把尺子判。
    """
    return service_key_parts(row)[0]


def _gap(row: dict, bks: tuple) -> dict:
    veh_gap = row["veh"] - bks[0]
    dist_gap = (row["dist"] / bks[1] - 1) * 100.0 if bks[1] > 0 else 0.0
    out = {"veh": row["veh"], "bks_veh": bks[0], "dist": row["dist"],
           "bks_dist": bks[1], "veh_gap": veh_gap}
    if veh_gap == 0:
        out["dist_gap_pct"] = round(dist_gap, 3)
    # 多开一辆车比里程更重的退步：单列计罚，不混进里程均值。
    # 丢单同样计罚（每单 25 个百分点）：否则「少带一单」会表现为里程下降，
    # 算法可以靠舍弃订单刷低 gap —— 那是把活推给别人，不是把路线排好。
    dropped = float(row.get("unassigned") or 0)
    out["dropped_orders"] = int(dropped)
    out["penalized_gap_pct"] = round(veh_gap * 50.0 + dist_gap + dropped * 25.0, 3)
    return out


def aggregate(instances: list[Instance], rows: list[dict], ref_map: dict | None = None) -> dict:
    """ref_map 给定时用它做 BKS（冻结标尺）：同一把尺子量所有版本，
    避免「这轮少跑了 long 臂 → gap 看起来变好」这种自证式改善。"""
    by_inst: dict[str, list[dict]] = {}
    for r in rows:
        by_inst.setdefault(r["instance_id"], []).append(r)
    inst_by_id = {i.instance_id: i for i in instances}

    per_inst: dict[str, dict] = {}
    n = {"serve": 0, "reject": 0, "verify": 0}
    ok = {"serve_any": 0, "serve_all": 0, "reject": 0, "verify": 0}
    invalid_rows = exceptions = veh_deficits = seed_flips = 0
    gaps: list[float] = []
    pen: list[float] = []
    budget_deficits: list[float] = []
    anytime_pairs = anytime_ties = anytime_better = 0
    anytime_violations: list[dict] = []
    seed_stds: list[float] = []
    walls: dict[str, list[float]] = {}
    soft_pairs: list[dict] = []      # 同实例同种子：软约束臂 vs 不做交接的反事实臂
    coverages: list[float] = []
    route_kms: list[float] = []
    walk_kms: list[float] = []
    system_kms: list[float] = []

    for iid, rs in by_inst.items():
        inst = inst_by_id[iid]
        for r in rs:
            if r.get("error"):
                exceptions += 1
            walls.setdefault(r["arm"], []).append(r.get("wall_ms", 0.0))
        good = [r for r in rs if r.get("status") == "feasible" and r.get("valid")
                and r["arm"] != "softfree"]
        invalid_rows += sum(1 for r in rs if r.get("status") == "feasible" and not r.get("valid"))
        inline_bks = min((solution_key_from(r) for r in good), default=None)
        if ref_map and iid in ref_map:
            bks = tuple(ref_map[iid])
        else:
            bks = inline_bks
        mine = [r for r in rs if r["arm"] == "haco"]
        feas = [r for r in mine if r.get("status") == "feasible" and r.get("valid")]
        entry: dict = {"expect": inst.expect, "family": inst.family, "tier": inst.tier,
                       "haco_seeds": f"{len(feas)}/{len(mine)}"}
        n[inst.expect] = n.get(inst.expect, 0) + 1

        if inst.expect == "serve":
            if feas:
                ok["serve_any"] += 1
            if mine and len(feas) == len(mine):
                ok["serve_all"] += 1
            sh = [r for r in rs if r["arm"] == "short"]
            if sh:
                n["short_rows"] = n.get("short_rows", 0) + len(sh)
                ok["short"] = ok.get("short", 0) + sum(
                    1 for r in sh if r.get("status") == "feasible" and r.get("valid"))
            free = {r["seed"]: r for r in rs if r["arm"] == "softfree"}
            for r in mine:
                f = free.get(r["seed"])
                if f is None or not (r.get("status") == "feasible"
                                     and f.get("status") == "feasible"):
                    continue
                soft_pairs.append({
                    "instance_id": iid, "seed": r["seed"],
                    "dist_soft": r["dist"], "dist_free": f["dist"],
                    "veh_soft": r["veh"], "veh_free": f["veh"],
                    "ho_soft": r.get("relocations", 0), "ho_free": f.get("relocations", 0),
                    "max_detour_soft": r.get("max_detour"),
                })
            ho_rows = [r for r in mine if r.get("status") == "feasible"]
            if ho_rows:
                n["cargo_stops"] = n.get("cargo_stops", 0) + sum(
                    r.get("cargo_stops", 0) for r in ho_rows)
                ok["relocations"] = ok.get("relocations", 0) + sum(
                    r.get("relocations", 0) for r in ho_rows)
            if len({r.get("status") == "feasible" for r in mine}) > 1:
                seed_flips += 1
            ds = [r["dist"] for r in feas]
            if len(ds) > 1:
                seed_stds.append(statistics.pstdev(ds) / max(statistics.fmean(ds), 1e-9) * 100.0)
            if bks and feas:
                best_mine = min(feas, key=solution_key_from)
                g = _gap(best_mine, bks)
                entry.update(g)
                dropped = int(best_mine.get("unassigned") or 0)
                entry["coverage"] = round(1.0 - dropped / max(1, inst.n_orders), 4)
                coverages.append(entry["coverage"])
                route_kms.append(best_mine["dist"])
                walk_kms.append(best_mine.get("walk_km", 0.0))
                system_kms.append(best_mine["dist"] + best_mine.get("walk_km", 0.0))
                if g["veh_gap"] > 0:
                    veh_deficits += 1
                if "dist_gap_pct" in g:
                    gaps.append(g["dist_gap_pct"])
                pen.append(g["penalized_gap_pct"])
        elif inst.expect == "reject":
            # 「reject」= 这道题不可能**全量**服务（语料用 Σ需求 > Σ舱位 或
            # 承诺时刻短于物理下界来保证可证明）。
            # 2026-10-11 政策变更（规格 §二.5 订单可舍弃 + 乘客不可舍底线）：
            # 超舱/超时的正确出口从「整批判无解」变成「能带的照走、带不动的如实
            # 进 unassignedOrderIds」，所以合格判据同步换成实质不变量：
            #   要么拒判无解且原因正当；
            #   要么交方案，但必须 (a) 通过独立校验（不超载、不把没带的算成已服务）
            #              且 (b) 至少申报一单未服务。
            # 给出「全量已服务」的方案仍然算错 —— 这是换了判据的对象，不是放宽尺子。
            def _honest(r: dict) -> bool:
                if r.get("status") != "feasible":
                    return r.get("reason") in REJECT_OK
                return bool(r.get("valid")) and int(r.get("unassigned") or 0) > 0

            if mine and all(_honest(r) for r in mine):
                ok["reject"] += 1
            entry["statuses"] = sorted({_status_tag(r) for r in mine})
        else:  # verify：状态不限，但返回的方案必须合规
            # `status="error"`（solve 抛异常 / 取不到题）不属于"状态不限"里的任何一种状态 ——
            # 它根本没交方案。旧写法 `!= "feasible"` 会把它算成合规 ⇒ 一道崩题记成一个绿格。
            goodc = sum(1 for r in mine
                        if r.get("status") not in (None, "error")
                        and (r.get("status") != "feasible" or r.get("valid")))
            if mine and goodc == len(mine):
                ok["verify"] += 1
            entry["statuses"] = sorted({_status_tag(r) for r in mine})

        # ── anytime（任务 #6）：预算 ×5 只允许让业务键不变或更好 ──
        # 必须同种子配对：long 臂只求天花板、只跑 seeds[0]（见 _collect），而 haco 臂跑
        # 全部种子；旧写法取 min(haco 全种子) vs min(long 单种子)，把「种子换来的 basin 差」
        # 读成了「预算差」—— d15 据此报的 5 例违背里，r_village-L-000 同种子其实是改善。
        # 写在 if/elif 链**之外**：这段以前只在 serve 分支里跑，于是 reject 题（两臂都交
        # 部分解）整类不进判据 —— 实测漏掉 r_deadline-L-005「25k 舍 2 单、125k 舍 3 单」
        # 这种最该报的服务量倒退。
        long_by_seed = {
            r["seed"]: r for r in rs
            if r["arm"] == "long" and r.get("valid") and r.get("status") == "feasible"
        }
        for a in feas:
            b = long_by_seed.get(a["seed"])
            if b is None:
                continue
            anytime_pairs += 1
            entry["long_dist"] = b["dist"]
            # headroom 口径不变：同车数下 (haco/long − 1)，**正数=更大预算还有余量没吃到**，
            # 与"违背"方向相反，两把尺子都要留着才骗不了人。必须写在分类之前，
            # 否则下面的 continue 会把它一起跳过（实测 mean_budget_deficit_pct 直接变 None）。
            if a["veh"] == b["veh"] and b["dist"] > 0:
                pd = round((a["dist"] / b["dist"] - 1) * 100, 2)
                entry["budget_deficit_pct"] = pd
                if pd > 0:
                    budget_deficits.append(pd)
            ka, names_a = service_key_parts(a)
            kb, _names_b = service_key_parts(b)
            delta = round(b["dist"] - a["dist"], 3)
            if kb == ka:
                anytime_ties += 1
                continue
            if kb < ka:
                anytime_better += 1
                continue
            # 键变差：取第一个不等的维度（它前面的维度已证明相等，所以它就是变差那一维）
            dim = "distance"
            for va, vb, nm in zip(ka, kb, names_a):
                if va != vb:
                    dim = nm
                    break
            if dim == "distance" and abs(delta) <= ANYTIME_NOISE_KM:
                anytime_ties += 1     # 同一条路线，只差浮点累加尾数
                continue
            anytime_violations.append({
                "instance_id": iid, "seed": a["seed"], "dim": dim, "expect": inst.expect,
                "un": (ka[0], kb[0]), "veh": (ka[1], kb[1]),
                "dist": (ka[2], kb[2]) if len(ka) == 3 else (a["dist"], b["dist"]),
                "key": (list(ka), list(kb)),
                # 绕行维度的可读旁证：老台账没有 objective_key 时靠它人工判读，
                # 免得把「少绕行换里程」当成缺陷（新台账已由 objective_key 覆盖这一维）
                "max_detour": (a.get("max_detour"), b.get("max_detour")),
                "delta_km": delta,
                "evals": (a.get("evaluations"), b.get("evaluations")),
            })
        per_inst[iid] = entry

    def _p(arm: str, q: float) -> float:
        v = sorted(walls.get(arm, [0.0]))
        if not v:
            return 0.0
        return round(v[min(len(v) - 1, max(0, int(len(v) * q) - 1))], 1)

    fam: dict[str, dict] = {}
    for iid, e in per_inst.items():
        if e["expect"] != "serve":
            continue
        k = f"{e['family']}/{e['tier']}"
        d = fam.setdefault(k, {"n": 0, "all_seed_solved": 0, "gaps": []})
        d["n"] += 1
        a, b = e["haco_seeds"].split("/")
        if a == b:
            d["all_seed_solved"] += 1
        if e.get("dist_gap_pct") is not None:
            d["gaps"].append(e["dist_gap_pct"])

    soft_delta = [(p["dist_soft"] / p["dist_free"] - 1) * 100.0
                  for p in soft_pairs
                  if p["dist_free"] > 0 and p["veh_soft"] == p["veh_free"]]
    soft_ab = {
        "paired_runs": len(soft_pairs),
        "instances_affected": len({p["instance_id"] for p in soft_pairs
                                   if p["ho_soft"] > 0}),
        "relocations_total": sum(p["ho_soft"] for p in soft_pairs),
        "mean_detour_km_before_rewrite": (
            round(statistics.fmean([p["max_detour_soft"] for p in soft_pairs
                                    if p.get("max_detour_soft")]), 3)
            if any(p.get("max_detour_soft") for p in soft_pairs) else None),
        "mean_dist_delta_pct": round(statistics.fmean(soft_delta), 3) if soft_delta else None,
        "median_dist_delta_pct": round(statistics.median(soft_delta), 3) if soft_delta else None,
    }

    return {
        "counts": {**n, "rows": len(rows)},
        "soft_constraint_ab": soft_ab,
        "serve": {
            "feasible_any_seed": round(ok["serve_any"] / n["serve"], 4) if n["serve"] else None,
            "feasible_all_seeds": round(ok["serve_all"] / n["serve"], 4) if n["serve"] else None,
            "feasible_short_budget": (round(ok.get("short", 0) / n["short_rows"], 4)
                                      if n.get("short_rows") else None),
            "mean_dist_gap_pct_vs_bks": round(statistics.fmean(gaps), 3) if gaps else None,
            "p95_dist_gap_pct_vs_bks": round(sorted(gaps)[max(0, int(len(gaps) * 0.95) - 1)], 3) if gaps else None,
            "worst_dist_gap_pct_vs_bks": round(max(gaps), 3) if gaps else None,
            "n_gapped": len(gaps),
            "mean_penalized_gap_pct": round(statistics.fmean(pen), 3) if pen else None,
            "vehicle_deficit_instances": veh_deficits,
            "mean_budget_deficit_pct": round(statistics.fmean(budget_deficits), 3) if budget_deficits else None,
            "relocation_rate": (round(ok.get("relocations", 0) / n["cargo_stops"], 4)
                              if n.get("cargo_stops") else None),
            # 绝对成本（实例集冻结，因此跨版本可直接对比）：
            # system = 车辆里程 + 用户走到交接点的里程，防止靠「把活推给用户」刷低里程。
            "mean_coverage": round(statistics.fmean(coverages), 4) if coverages else None,
            "min_coverage": round(min(coverages), 4) if coverages else None,
            "mean_route_km": round(statistics.fmean(route_kms), 3) if route_kms else None,
            "mean_walk_km": round(statistics.fmean(walk_kms), 4) if walk_kms else None,
            "mean_system_km": round(statistics.fmean(system_kms), 3) if system_kms else None,
            "total_system_km": round(sum(system_kms), 2) if system_kms else None,
        },
        "anytime": {
            # 判据（任务 #6）：同一实例、同一 seed、同一份代码，预算 25k → 125k
            # 只允许 service_key 不变或更好。配对与维度定位见上面的循环。
            "paired_same_seed": anytime_pairs,
            "ties": anytime_ties,
            "improvements": anytime_better,
            "violations": len(anytime_violations),
            "by_dim": {d: sum(1 for v in anytime_violations if v["dim"] == d)
                       for d in sorted({v["dim"] for v in anytime_violations})},
            "worst_delta_km": (round(max(abs(v["delta_km"]) for v in anytime_violations), 3)
                               if anytime_violations else 0.0),
            "list": anytime_violations,
        },
        "robustness": {
            "seed_flip_instances": seed_flips,
            "mean_seed_dist_cv_pct": round(statistics.fmean(seed_stds), 4) if seed_stds else None,
            "worst_seed_dist_cv_pct": round(max(seed_stds), 4) if seed_stds else None,
            "invalid_plan_rows": invalid_rows,
            "exceptions": exceptions,
        },
        "reject": {"total": n["reject"],
                   "correct_rate": round(ok["reject"] / n["reject"], 4) if n["reject"] else None},
        "verify": {"total": n["verify"],
                   "correct_rate": round(ok["verify"] / n["verify"], 4) if n["verify"] else None},
        "wall_ms": {a: {"p50": _p(a, 0.5), "p95": _p(a, 0.95)} for a in sorted(walls)},
        "by_family": {k: {"n": v["n"], "all_seed_solved": v["all_seed_solved"],
                          "mean_gap_pct": round(statistics.fmean(v["gaps"]), 3) if v["gaps"] else None}
                      for k, v in sorted(fam.items())},
        "per_instance": per_inst,
    }


METRIC_KEYS = [
    ("serve", "feasible_any_seed"), ("serve", "feasible_all_seeds"),
    ("serve", "feasible_short_budget"),
    ("serve", "mean_dist_gap_pct_vs_bks"), ("serve", "worst_dist_gap_pct_vs_bks"),
    ("serve", "mean_penalized_gap_pct"), ("serve", "vehicle_deficit_instances"),
    ("serve", "mean_coverage"), ("serve", "min_coverage"), ("serve", "relocation_rate"),
    ("serve", "mean_budget_deficit_pct"), ("serve", "relocation_rate"),
    ("robustness", "seed_flip_instances"), ("robustness", "mean_seed_dist_cv_pct"),
    ("robustness", "invalid_plan_rows"), ("robustness", "exceptions"),
    ("reject", "correct_rate"), ("verify", "correct_rate"),
    ("soft_constraint_ab", "relocations_total"),
    ("soft_constraint_ab", "instances_affected"),
    ("soft_constraint_ab", "mean_dist_delta_pct"),
]


def _diff(new_path: str, ref_path: str) -> None:
    if not os.path.exists(ref_path):
        print(f"[diff] 参照台账不存在：{ref_path}")
        return
    with open(new_path, encoding="utf-8") as fh:
        new = json.load(fh)["summary"]
    with open(ref_path, encoding="utf-8") as fh:
        ref = json.load(fh)["summary"]
    print(f"\n[diff] vs {os.path.basename(ref_path)}")
    for sec, k in METRIC_KEYS:
        a, b = ref.get(sec, {}).get(k), new.get(sec, {}).get(k)
        if a is None or b is None:
            print(f"  {sec}.{k:28s} {a} -> {b}")
        else:
            print(f"  {sec}.{k:28s} {a} -> {b}  ({b - a:+.4g})")


def _recompute(tag: str, ref_file: str, diff_tag: str) -> None:
    """用已存台账的 rows 重新聚合一版指标（零算力换同一把尺子）。

    冻结标尺中途扩充（如修好某族后该族才第一次有有效参照）时，
    必须把历史版本一起重算，否则版本间的 gap 不是同一个分母。
    """
    path = os.path.join(LEDGER_DIR, f"{tag}.json")
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    picked = [x for x in (_load(iid) for iid in {r["instance_id"] for r in doc["rows"]}) if x]
    ref_map = None
    if ref_file:
        with open(os.path.join(LEDGER_DIR, ref_file), encoding="utf-8") as fh:
            ref_map = json.load(fh)["best"]
    summary = aggregate(picked, doc["rows"], ref_map)
    doc["summary"] = summary
    doc["ref_file"] = ref_file or "inline"
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
    print(json.dumps({k: v for k, v in summary.items() if k != "per_instance"},
                     ensure_ascii=False, indent=1))
    if diff_tag:
        _diff(path, os.path.join(LEDGER_DIR, f"{diff_tag}.json"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", default="", help="版本台账名，如 v0 / v1_xxx")
    ap.add_argument("--splits", default="train,val", help="逗号分隔 train,val,test 或 all")
    ap.add_argument("--seeds", type=int, default=2)
    ap.add_argument("--jobs", type=int, default=DEFAULT_JOBS)
    ap.add_argument("--per-cell", type=int, default=6)
    ap.add_argument("--arms", default="haco,baseline,long")
    ap.add_argument("--seed-base", type=int, default=20261010)
    ap.add_argument("--corpus", default="real",
                    choices=("real", "synthetic", "both", "random", "online"),
                    help="real=重庆真实路网固定语料；random=每轮现造的真实实例（防过拟合）；"
                         "synthetic=合成几何对照；online=现网请求留痕快照（只出 verify 题）")
    ap.add_argument("--epoch", type=int, default=1,
                    help="random 语料的抽样轮次：换 epoch 就是换一批真实线路题")
    ap.add_argument("--n-random", type=int, default=60,
                    help="random 语料每轮抽多少个实例")
    ap.add_argument("--ref", default="", help="对照台账 tag；给出则打印差值")
    ap.add_argument("--recompute", default="", metavar="TAG",
                    help="不求解，只用 ledger/TAG.json 里已存的 rows 重新聚合")
    ap.add_argument("--ref-file", default="",
                    help="冻结标尺文件（相对 ledger 目录）；缺省按语料自动选")
    ap.add_argument("--save-ref", action="store_true", help="把本轮结果写进 --ref-file")
    args = ap.parse_args()

    if args.recompute:
        _recompute(args.recompute, args.ref_file if os.path.exists(
            os.path.join(LEDGER_DIR, args.ref_file)) else "", args.ref)
        return

    allinst: list[Instance] = []
    if args.corpus == "random":
        from .real_corpus import random_corpus
        allinst = random_corpus(n=args.n_random, epoch=args.epoch)
        print(f"[lab] 随机真实语料 epoch={args.epoch}：{len(allinst)} 个现造实例", flush=True)
    elif args.corpus == "online":
        from . import online_corpus
        # --n-random 在这里当"最多取几份快照"用：OD 冷启动是这轮最贵的一段，
        # 想先探通路就调小，别为了看一眼前程把整个集合重算一遍
        allinst = online_corpus.corpus(limit=args.n_random)
        print(f"[lab] 线上留痕语料：{len(allinst)} 道 verify 题"
              "（没有见证解也没有拒绝证明 ⇒ 只判方案合规，不进 serve/reject 分母）", flush=True)
    else:
        if args.corpus in ("synthetic", "both"):
            from .instances import corpus as build_corpus
            allinst += build_corpus(per_cell=args.per_cell, ramp=5)
        if args.corpus in ("real", "both"):
            from .real_corpus import build_corpus as build_real_corpus
            allinst += build_real_corpus(per_cell=args.per_cell, ramp=4)
    # online 集不做 60/20/20 抽样：题集随现网流量增长，抽样切分只会让两轮读数对不上题；
    # 它也从不参与调参，所以"train/val/test"这套为防过拟合设计的划分对它没有意义。
    want = None if (args.splits == "all" or args.corpus == "online") else set(args.splits.split(","))
    picked = [i for i in allinst if want is None or split(i.instance_id) in want]
    if not args.ref_file:
        args.ref_file = ("reference_real.json" if args.corpus == "real"
                         else "reference_online.json" if args.corpus == "online"
                         else "reference.json")
    seeds = [args.seed_base + k for k in range(args.seeds)]
    arms = tuple(a for a in ARM_ORDER if a in args.arms.split(","))
    ref_path = os.path.join(LEDGER_DIR, args.ref_file)
    ref_map: dict | None = None
    if os.path.exists(ref_path):
        with open(ref_path, encoding="utf-8") as fh:
            ref_map = json.load(fh).get("best", {})
        # 命中数必须打出来：标尺文件存在 ≠ 它覆盖本轮实例。
        # 曾经就是「文件在、id 对不上」把 gap 的分母换成了旧内容（读数出现 404% 的假极值），
        # 而日志只显示「已加载 N 个实例」，看不出一个都没命中。
        hit = sum(1 for i in picked if i.instance_id in ref_map)
        print(f"[lab] 冻结标尺 {args.ref_file}：文件内 {len(ref_map)} 个实例，"
              f"本轮命中 {hit}/{len(picked)}"
              + ("" if hit else "（→ 实际按本轮内联 BKS 计 gap，与其他轮的冻结标尺读数不可直接比）"),
              flush=True)
    print(f"[lab] tag={args.tag} instances={len(picked)} arms={arms} seeds={seeds}", flush=True)

    rows = _collect([i.instance_id for i in picked], arms, seeds, args.jobs)
    summary = aggregate(picked, rows, ref_map)
    if args.save_ref:
        best: dict[str, list] = {}
        for r in rows:
            if r.get("status") != "feasible" or not r.get("valid"):
                continue
            k = list(solution_key_from(r))
            iid = r["instance_id"]
            if iid not in best or k < best[iid]:
                best[iid] = k
        with open(ref_path, "w", encoding="utf-8") as fh:
            json.dump({"saved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                       "arms": list(ARM_ORDER), "seeds": seeds, "best": best},
                      fh, ensure_ascii=False, indent=1)
        print(f"[lab] 冻结标尺已更新 -> {ref_path}（{len(best)} 个实例）")
    doc = {"tag": args.tag,
           "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
           "splits": args.splits, "arms": list(arms), "seeds": seeds,
           # gap 的分母是谁：不留这个字段，隔一轮回读台账就分不清
           # 「本轮内联 BKS」还是「某个冻结参照」——两者数值不可比。
           "ref_file": (args.ref_file if ref_map is not None else "inline"),
           "n_instances": len(picked), "summary": summary, "rows": rows}
    os.makedirs(LEDGER_DIR, exist_ok=True)
    path = os.path.join(LEDGER_DIR, f"{args.tag}.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)

    print(json.dumps({k: v for k, v in summary.items() if k != "per_instance"},
                     ensure_ascii=False, indent=1))
    if args.ref:
        _diff(path, os.path.join(LEDGER_DIR, f"{args.ref}.json"))
    print(f"[lab] ledger -> {path}")


if __name__ == "__main__":
    main()
