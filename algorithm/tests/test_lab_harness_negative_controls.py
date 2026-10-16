"""度量台自身的负控：证明「尺子」不是摆设。

为什么要这些用例：lab 的指标（可行率 / gap / 校验通过率）只有在
「方案违规时 check_plan 一定会抓到」的前提下才有意义。否则一次「全部绿色」
的读数可能只是校验器瞎了。每条负控都配一个具名的正向用例做对照。
"""

from __future__ import annotations

import copy

import pytest

from app.models import RouteStop, StopAction

from benchmarks.lab import real_corpus as real
from benchmarks.lab.check import check_plan
from benchmarks.lab.instances import build, dump, load, split, witness
from benchmarks.lab.run import _cfg, solve

SERVE_IDS = ["vrp-S-000", "corridor-S-000", "village-M-001", "multidepot-S-002"]
REJECT_IDS = ["cap_tight-S-000", "cap_tight-M-000"]


def _need(iid: str):
    """取一个具名合成样本；cache 里没有就**确定性现算一份再落盘**。

    为什么要在测试里补这一步：`benchmarks/lab/cache/*.json` 是跑出来的产物，没有入库
    （530 个文件 / 17MB，且它会随每次实验重写 ⇒ 入库只会在每次跑完造出假差异）。
    而这批样本是**可复现的**：`build()` 的随机源是 `_stable_seed(instance_id)`，
    同一 id 在任何机器上造出同一个实例。所以缺文件不等于"这题不存在"，更不等于可以跳过 ——
    跳过会让这 20 条在 CI 里恒绿却什么都没验（正是这本负控自己要抓的那种"尺子瞎了"）。
    现算也造不出来才是真事故（造例器退化了），那时 assert 会明说，不静默。
    """
    inst = load(iid)
    if inst is not None:
        return inst
    family, tier, index = iid.rsplit("-", 2)
    built = build(family, tier, int(index))
    assert built is not None, f"{iid} 既没入库、现算也造不出（造例器退化，不是题集丢了）"
    dump(built)
    again = load(iid)
    assert again is not None, f"{iid} 现算后仍读不到，落盘路径与读取路径不一致"
    return again


def _solve(iid: str, mode: str = "BASELINE"):
    """跑一次求解供校验器用例使用。

    默认用 BASELINE 臂：校验器与「哪个求解器产出方案」无关，用确定性且毫秒级的
    臂既稳又快。（用 HACO 短预算当正控会被「1s 预算造不出初始可行解」这个已知
    缺陷干扰，那是算法的事，不是尺子的事。）
    """
    inst = _need(iid)
    # 测试只要「有一份方案可校验」，不需要生产级搜索预算：钉 3000 次评估，秒级返回
    req = inst.request.model_copy(update={"algorithmConfig": _cfg(mode, 20261010, 3000)})
    return inst, req, solve(req, inst.matrix)


def test_corpus_labels_are_present():
    """语料不能空转：serve/reject 都要有具名样本，否则指标是在 0 个实例上算的。"""
    for iid in SERVE_IDS:
        inst = _need(iid)
        assert inst is not None, f"{iid} 未持久化，度量台无米下锅"
        assert inst.expect == "serve"
        assert inst.witness_feasible is True
    for iid in REJECT_IDS:
        inst = _need(iid)
        assert inst is not None
        assert inst.expect == "reject"
    assert split("vrp-S-000") in ("train", "val", "test")


def test_reject_sample_is_provably_over_capacity():
    """cap_tight 的「应拒绝」标签要有算术证明：单方向（揽收）需求确实超总运力。

    历史坑：早先按「派送+揽收总和 > 运力」造标签，而货仓是出程派送、返程揽收
    依次复用的（solver precheck 也分向比较），于是把可解实例标成「必须拒绝」，
    算法正常出方案反而被判错。
    """
    for iid in REJECT_IDS:
        inst = _need(iid)
        pickups = sum(o.itemCount for o in inst.request.orders
                      if o.orderType.value == "PICKUP")
        pickups += sum(s.quantity for s in inst.request.shipments)
        cap = sum(v.cargoCapacity for v in inst.request.vehicles)
        assert pickups > cap, f"{iid} 揽收需求 {pickups} 未超运力 {cap}，reject 标签不成立"


def test_witness_agrees_with_solver_on_serve_instances():
    """正向对照：serve 实例的见证必须成立（不带搜索预算的构造即可解）。"""
    inst = _need("corridor-S-000")
    ok, why = witness(inst.request, inst.matrix, use_window=True)
    assert ok, f"corridor-S-000 见证失败：{why}"


def test_check_plan_accepts_real_solution():
    """正向对照：真解应当 0 违规。"""
    inst, req, out = _solve("vrp-S-000")
    assert out.status == "feasible", out.reason_code
    assert check_plan(req, inst.matrix, out) == []


def test_check_plan_catches_dropped_passenger_alighting():
    """负控 1：抹掉乘客下车停靠 → 必须抓到 PAX_SPLIT（而不是放行）。"""
    inst, req, out = _solve("vrp-S-000")
    assert out.status == "feasible"
    bad = copy.deepcopy(out)
    for plan in bad.vehicle_plans:
        plan.stops = [s for s in plan.stops if s.action != StopAction.ALIGHT]
    errs = check_plan(req, inst.matrix, bad)
    assert any(e.startswith("PAX_SPLIT") for e in errs), errs


def test_check_plan_catches_unserved_order():
    """负控 2：整单删掉（BOARD+ALIGHT 同删）→ 必须抓到 ORDER_UNSERVED。"""
    inst, req, out = _solve("vrp-S-000")
    assert out.status == "feasible"
    bad = copy.deepcopy(out)
    oid = next(s.orderId for p in bad.vehicle_plans for s in p.stops if s.orderId)
    for plan in bad.vehicle_plans:
        plan.stops = [s for s in plan.stops if s.orderId != oid]
    errs = check_plan(req, inst.matrix, bad)
    assert any(e.startswith("ORDER_UNSERVED") for e in errs), errs


def test_check_plan_catches_inflated_distance():
    """负控 3：把里程改小（刷 gap 的作弊路径）→ 必须抓到里程复算不一致。"""
    inst, req, out = _solve("vrp-S-000")
    assert out.status == "feasible"
    bad = copy.deepcopy(out)
    bad.total_distance = bad.total_distance * 0.5
    errs = check_plan(req, inst.matrix, bad)
    assert any(e.startswith("OUTCOME_TOTAL_MISMATCH") for e in errs), errs


def test_check_plan_catches_capacity_violation():
    """负控 4：凭空插入一个揽收停靠 → 载荷/覆盖校验必须报警。"""
    inst, req, out = _solve("corridor-M-000")
    if out.status != "feasible":
        pytest.skip("corridor-M-000 本次未给出方案，改日再验")
    bad = copy.deepcopy(out)
    plan = bad.vehicle_plans[0]
    plan.stops.insert(1, RouteStop(stationId=plan.stops[1].stationId,
                                   orderId="GHOST1", action=StopAction.PICKUP,
                                   segmentDistance=0.0, segmentDuration=0.0))
    errs = check_plan(req, inst.matrix, bad)
    assert errs, "凭空插入揽收站竟无违规检出，校验器失效"


def test_check_plan_catches_skeleton_order_violation():
    """负控 5：删掉骨架中段的一个 PASS 停靠 → 骨架顺序校验必须报警。"""
    inst, req, out = _solve("corridor-S-000")
    assert out.status == "feasible"
    bad = copy.deepcopy(out)
    plan = bad.vehicle_plans[0]
    passes = [i for i, s in enumerate(plan.stops) if s.action == StopAction.PASS]
    assert len(passes) >= 3, "corridor-S-000 骨架停靠不足，无法构造负控"
    drop = passes[len(passes) // 2]
    bad.vehicle_plans[0].stops = [s for i, s in enumerate(plan.stops) if i != drop]
    errs = check_plan(req, inst.matrix, bad)
    assert any(e.startswith("SKELETON") for e in errs), errs


def test_check_plan_catches_fake_nearest_station_handover():
    """负控 7：求解器声称「超阈值已改到就近骨架站」但停靠其实不在骨架站上 → 必须抓到。

    真实路网语料（r_village）才有超阈值绕行，合成族的收货点都在骨架上，
    所以这条用真实实例；实例 JSON 自带 OD 矩阵，测试不需要加载 341MB 路网图。
    """
    inst = real.load("r_village-M-002")
    if inst is None:
        pytest.skip("真实语料未生成，先跑 python -m benchmarks.lab.real_corpus 生成器")
    req = inst.request.model_copy(update={"algorithmConfig": _cfg("HACO", 20261010, 25_000)})
    out = solve(req, inst.matrix)
    assert out.status == "feasible", out.reason_code
    # 这条用例只关心「交接是否被冒充」。整方案零违规不是它的判据：
    # 该实例本身可能有超阈值在车绕行（那是任务 #12 的出口收敛缺口，另有用例钉住）。
    handover_errs = [e for e in check_plan(req, inst.matrix, out)
                     if e.startswith(("HANDOVER_", "SERVICE_POINT_", "UNEXPLAINED_"))]
    assert handover_errs == [], handover_errs
    relocated = [s for p in out.vehicle_plans for s in p.stops
                 if s.reasonCode == "DETOUR_OVER_THRESHOLD"]
    if not relocated:
        pytest.skip("本轮 r_village-M-002 没有触发交接改写，改日再验")
    skel = set(next(v for v in inst.request.vehicles
                    if v.vehicleId == out.vehicle_plans[0].vehicleId).skeleton or [])
    assert skel, "r_village 车辆必须带真实公交骨架"
    off_skel = [s.stationId for s in inst.request.stations if s.stationId not in skel]
    assert off_skel, "语料里必须有非骨架点，否则造不出假交接"
    bad = copy.deepcopy(out)
    for p in bad.vehicle_plans:
        for s in p.stops:
            if s.reasonCode == "DETOUR_OVER_THRESHOLD":
                s.stationId = off_skel[0]
    errs = check_plan(req, inst.matrix, bad)
    assert any(e.startswith("HANDOVER_NOT_AT_SKELETON") for e in errs), errs


def test_check_plan_catches_wrong_start_station():
    """负控 6：多起点公交线的发班/收班站不对 → 校验器必须抓到。

    正向对照：baseline 臂实现了 startStationId/endStationId，方案应 0 违规。
    （HACO 臂目前全链不读这两个字段，那条缺口由 lab 台账与任务 #5 追，
    不写进测试断言，免得修好后测试反过来变红。）
    """
    inst, req, out = _solve("multidepot-S-002")
    if out.status != "feasible":
        pytest.skip("multidepot-S-002 本次未给出方案，改日再验")
    veh = inst.request.vehicles[0]
    assert veh.startStationId and veh.endStationId, "multidepot 样本必须带始发/收班站"
    assert check_plan(req, inst.matrix, out) == []

    bad = copy.deepcopy(out)
    p = bad.vehicle_plans[0]
    p.stops[0] = p.stops[0].model_copy(update={"stationId": "D"})
    p.stops[-1] = p.stops[-1].model_copy(update={"stationId": "D"})
    errs = check_plan(req, inst.matrix, bad)
    assert any(e.startswith("START_STATION") for e in errs), errs
    assert any(e.startswith("END_STATION") for e in errs), errs
