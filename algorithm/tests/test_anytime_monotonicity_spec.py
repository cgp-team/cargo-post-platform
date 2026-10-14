"""预算单调性锚点（任务 #6）：同题、同种子、同代码，5 倍评估预算不许让业务键变差。

为什么单独立一个文件：台账里的 `anytime` 读数是聚合口径，题目一换就查不出"这一道
到底合不合规"。这里钉的是**具名实例**，红因直接对上 §17 的归因。

尺子用度量台自己的 `service_key`（服务量 → 用车数 → 乘客影响 → 货运绕行 → 里程），
不要在测试里另造一把——历史上"违背 5 例"就是因为在两处各写了一套比较规则而错判。
"""
from __future__ import annotations

import pytest

from benchmarks.lab.run import (  # noqa: E402
    ARM_DETOUR_KM,
    ARM_EVALS,
    _cfg,
    _load,
    service_key,
)
from app.solver import solve

pytestmark = pytest.mark.slow
SEED = 20261010


def _arm_row(iid: str, arm: str) -> dict:
    inst = _load(iid)
    req = inst.request.model_copy(update={
        "algorithmConfig": _cfg("HACO", SEED, ARM_EVALS[arm],
                                ARM_DETOUR_KM.get(arm))})
    out = solve(req, inst.matrix)
    assert out.status == "feasible", f"{iid}/{arm}: {out.reason_code}"
    plans = out.vehicle_plans or []
    return {
        "unassigned": len(out.unassigned_order_ids or []),
        "veh": sum(1 for p in plans if len(p.stops) > 1),
        "dist": round(float(out.total_distance), 3),
        "objective_key": list(getattr(out, "objective_key", ()) or ()),
    }


def _report(iid: str):
    a = _arm_row(iid, "haco")
    b = _arm_row(iid, "long")
    return a, b


def test_more_budget_must_not_worsen_service_key_r_village_M_002():
    """#6 的旗舰案例：d15 实测 25k=1 车/34.760km，125k 反而 2 车/38.604km（+11.06%）。

    根因不在随机流（把 ALNS 单独起流后违背换个实例就回来了，d16 实测），而在撤车搬运：
    贪心修复"差一件就判撤不掉"。加一层 ejection 后两臂都到 1 车/34.760km（§17）。
    """
    a, b = _report("r_village-M-002")
    assert service_key(b) <= service_key(a), (
        f"预算 ×5 后业务键变差：{a} -> {b}"
    )
    assert b["veh"] <= a["veh"], f"预算 ×5 反而多开一台：{a['veh']} -> {b['veh']}"


RESIDUAL = (
    "任务 #16/#27 残差：撤车搬运仍够不到更小的可行车数（3 台收不到 2 台），"
    "机制修好后本用例应转 XPASS，届时请摘掉标记"
)


@pytest.mark.xfail(reason=RESIDUAL, strict=False)
def test_more_budget_must_not_use_more_vehicles_r_multidepot_L_001():
    """红因实测（d15/d18 同种子）：2 台/55.992km -> 3 台/56.714km。"""
    a, b = _report("r_multidepot-L-001")
    assert b["veh"] <= a["veh"], f"{a} -> {b}"


@pytest.mark.xfail(reason="任务 #6 里程维残差：d15 实测 59.346km -> 60.213km，"
                          "两臂 un/veh/max_detour 全同，是纯搜索方差（见 §17）",
                  strict=False)
def test_more_budget_must_not_worsen_distance_r_line_L_004():
    a, b = _report("r_line-L-004")
    assert service_key(b) <= service_key(a), f"{a} -> {b}"
