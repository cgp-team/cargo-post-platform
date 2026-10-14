"""规模性质锚点：候选车队变大时，解必须不劣化（2026-10-11 起两条质量断言均已成立）。

现实形态：一条公交线一天发多班车，智能派单从「这条线的全部可用车」里挑几台。
用户已明确"一批能调的车不该只限 3 台，批次规模要按需求动态推导并算成本"
（`app/main.py` 的 `MAX_VEHICLES=3`、Java `MAX_ALGORITHM_VEHICLES=3`、
`docs/api/algorithm-api.yaml` 都写死了数字）。

数学依据：可选车变多是**放宽约束**，V=3 的解在 V=6 的问题里同样合法（多出的车闲置），
所以最优里程只会 ≤；用车数那一维更不该涨。

实测（真实路网 L 档实例 `r_line-L-000`，把原有 3 台车复制成 3/6/12 台对称班次，40k 评估）：

| 可选车 | 两处修好之前 | route-removal 之后 | 压实合并之后 |
|---|---|---|---|
| V=3 | 3 台 / 57.736 km | 3 台 / 57.736 km | 3 台 / 57.736 km |
| V=6 | 4 台 / 76.401 km | 4 台 / 76.401 km | **3 台 / 58.496 km**（仍差 0.76 km = 质量残差） |
| V=12 | 4 台 / 76.401 km | 3 台 / 57.737 km（**ALNS 抽样碰巧**，不是机制） | 3 台 / 57.737 km |

起效的是这两处，**不是**曾经写在这里的"构造侧只允许顺序开启"那套处方
（它已被实测否掉：真实车队每台有自己的骨架线路与始发/收班站，禁止开启后面那台会
合法地丢掉本可服务的订单 —— 少服务比里程变差更糟）：
1. ALNS 新增 `destroy_route`（route removal）—— 字典序目标里"关掉一台车"是纯赢，
   但既有动作全是任务级的，逐件搬空第一步必被拒（台账 §13）；
2. 压实阶段新增 `_consolidate_into` —— 原来的压实只会"从零重打一遍"（确定性弱贪心），
   V=6 时它在 `m=3` 产不出完整解，而容量下界又跳过了 `m=1/2`，等于唯一一次机会没抓住；
   新做法是保留已打包好的部分、只搬多余那台，且修复候选**只允许是在用车辆**
   （允许往空候选车插就会变成"挪车"而不是"撤车"，实测空转到预算超时，台账 §14.2）。

抬上限仍未解锁：V=6 的里程还差那 0.76 km，且换题轮 e8 仍有 2 个实例多用一台车。
详见 docs/algorithm/haco-cps-lab-iteration-2026-10.md §13、§14。
"""

from __future__ import annotations

import copy
import os

import pytest

from app.haco.config import HacoConfig
from app.haco.v14_solver import solve as solve_v14
from benchmarks.lab.roadnet import GRAPH_PKL, ROUTES_JSON

pytestmark = pytest.mark.slow

# 这三条钉的是**真实重庆线路**上的规模性质：骨架、站序、OD 全部来自实测图，
# 换成合成题就失去意义（合成题的站距分布是造例器给的，"车多了里程不该涨"在那种题上近乎恒真）。
# 而实测图是 341MB 的 data/road_graph_cq.pkl + data/transit 快照，都进不了仓库（GitHub 单文件硬限）。
# ⇒ 没有图的机器（含 CI）只能**跳过**，且跳过必须说清缺哪一件、怎么生成，
#   绝不让它读成"这条用例过了"。本机的跑法见 docs/REAL_ROAD_ROUTING_ARCHITECTURE.md。
_MISSING = [p for p in (GRAPH_PKL, ROUTES_JSON) if not os.path.exists(p)]
if _MISSING:
    pytest.skip(
        "真实路网输入缺失 ⇒ 跳过（不是通过）："
        + "、".join(os.path.basename(p) for p in _MISSING)
        + "；生成方式：python -m app.routing.osm_import 与 python -m benchmarks.lab.real_corpus",
        allow_module_level=True,
    )


def _real_line_L():
    """真实重庆线路实例（骨架/站序/OD 全部真实，非本用例构造）。"""
    from benchmarks.lab.real_corpus import build_corpus

    for inst in build_corpus(per_cell=4, ramp=4):
        if inst.instance_id == "r_line-L-000":
            return inst
    raise AssertionError("真实语料里找不到 r_line-L-000，用例失去前提")


def _fleet_variant(inst, factor: int):
    """把该线路的可用车复制成 factor 份对称班次（同骨架、同容量，只换 vehicleId）。"""
    req = copy.deepcopy(inst.request)
    vehicles = []
    base = list(req.vehicles)
    for j in range(len(base) * factor):
        v = copy.deepcopy(base[j % len(base)])
        v.vehicleId = 2000 + j
        vehicles.append(v)
    req.vehicles = vehicles
    return req


def _solve(inst, req, evals: int = 40_000):
    cfg = HacoConfig.from_algorithm_config(req.algorithmConfig)
    cfg.max_evaluations = evals
    cfg.haco_time_limit = 200.0        # 墙钟只当保险丝：判据挂在评估次数上
    cfg.overall_time_limit = 200.0
    return solve_v14(req, inst.matrix, cfg)


def _used(out) -> int:
    return sum(1 for p in out.vehicle_plans if len(p.stops) > 1)



def test_more_candidate_vehicles_must_not_worsen_distance():
    """同题、同评估预算：可选车 3→6 台，里程只能不变或更好。

    2026-10-11 起本条成立（此前 xfail）。实测读数：V=3 为 57.736km/3 台，
    V=6 为 58.496km/3 台 —— 用车数已经收住，里程仍比 V=3 大 1.3%。
    `V=3 的解在 V=6 里同样合法`，所以那 0.76km 是**质量残差**，不是政策代价；
    这里 2% 的容差是给搜索噪声留的余量，**不许**为了让本条变绿而把它放宽。
    """
    inst = _real_line_L()
    base = _solve(inst, _fleet_variant(inst, 1))
    wide = _solve(inst, _fleet_variant(inst, 2))
    assert base.status == "feasible" and wide.status == "feasible", \
        f"{base.reason_code} / {wide.reason_code}"
    assert wide.total_distance <= base.total_distance * 1.02, (
        f"可选车翻倍后里程从 {base.total_distance} 变差到 {wide.total_distance}"
    )


def test_more_candidate_vehicles_must_not_use_more_vehicles():
    """用车数是业务键第 2 维：多给的车应当闲置，而不是各跑一段。

    2026-10-11 起本条成立（此前 xfail，红因是 3 台 → 4 台）：
    12 台候选时实测仍用 3 台。收住它的是压实阶段的 `_consolidate_into`
    （撤装载最少那台 + 修复候选只允许是在用车辆），不是构造侧的顺序开启 —— 
    后者在真实车队里会合法丢单，见台账 §14.2。
    """
    inst = _real_line_L()
    base = _solve(inst, _fleet_variant(inst, 1))
    wide = _solve(inst, _fleet_variant(inst, 4))
    assert _used(wide) <= _used(base), (
        f"3 台可选时用车 {_used(base)}，12 台可选时却用 {_used(wide)}"
    )


def test_wider_fleet_never_drops_orders_or_breaks_validity():
    """反向保护：收缩车队不许成为丢单/违规的理由（这条是现在就成立的硬不变量）。"""
    from benchmarks.lab.check import check_plan

    inst = _real_line_L()
    for factor in (1, 2, 4):
        req = _fleet_variant(inst, factor)
        out = _solve(inst, req)
        assert out.status == "feasible", f"V×{factor} 判无解：{out.reason_code}"
        assert not out.unassigned_order_ids, \
            f"V×{factor} 出现丢单：{out.unassigned_order_ids}"
        assert check_plan(req, inst.matrix, out) == [], f"V×{factor} 方案不合规"
