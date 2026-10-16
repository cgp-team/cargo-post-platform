"""anytime 判据（任务 #6）的度量侧负控。

这里只测「尺子」，不跑求解器：尺子读错方向时，算法侧再怎么改都是在追幻影。
d15 台账就是这么被骗过一次 —— 旧写法拿 min(haco 全种子) 去比 min(long 单种子)，
而 long 臂按设计只跑第一个种子。
"""
from __future__ import annotations

import pytest

from benchmarks.lab.instances import Instance
from benchmarks.lab.run import ANYTIME_NOISE_KM, aggregate, service_key


def _inst(iid: str, n_orders: int = 10, expect: str = "serve") -> Instance:
    return Instance(
        instance_id=iid, family="line", tier="M", request=None, matrix={},
        expect=expect, witness_feasible=True, n_tasks=n_orders, n_orders=n_orders,
    )


def _row(iid: str, arm: str, seed: int, *, veh: int, dist: float,
         unassigned: int = 0) -> dict:
    return {
        "instance_id": iid, "arm": arm, "seed": seed,
        "status": "feasible", "reason": None, "valid": True, "errors": [],
        "veh": veh, "dist": round(dist, 3), "unassigned": unassigned,
        "evaluations": {"haco": 25_000, "long": 125_000}[arm],
        "fallback": "NONE", "cargo_stops": 4, "relocations": 0,
        "walk_km": 0.0, "max_detour": 0.0, "wall_ms": 1000.0, "version": "test",
    }


def _anytime(iid: str, *rows: dict, expect: str = "serve") -> dict:
    return aggregate([_inst(iid, expect=expect)], list(rows))["anytime"]


def test_reject_instances_are_paired_too():
    """判据过去只挂在 serve 分支上，于是 reject 题（两臂都交部分解）整类不进判据。

    实测漏掉的就是这一类最该报的形状：r_deadline-L-005 在 25k 舍 2 单、125k 舍 3 单，
    多花 5 倍预算反而少服务一单。
    """
    iid = "r_case-I-000"
    got = _anytime(iid,
                   _row(iid, "haco", 1, veh=2, dist=84.822, unassigned=2),
                   _row(iid, "long", 1, veh=2, dist=75.006, unassigned=3),
                   expect="reject")
    assert got["paired_same_seed"] == 1
    assert got["violations"] == 1
    assert got["list"][0]["dim"] == "unassigned"
    assert got["list"][0]["expect"] == "reject"

    # 三类 expect 都要配对得上：判据锁在某一分支 = 另外两类根本没有读数（当年的真实漏法）
    for expect in ("serve", "verify"):
        covered = _anytime(iid,
                           _row(iid, "haco", 1, veh=2, dist=84.822, unassigned=2),
                           _row(iid, "long", 1, veh=2, dist=75.006, unassigned=3),
                           expect=expect)
        assert covered["paired_same_seed"] == 1, expect
        assert covered["violations"] == 1, expect


def test_service_key_puts_coverage_before_cost():
    """丢单必须比里程更重：少带一单换短 10km 不许被读成「更好」。"""
    full = {"unassigned": 2, "veh": 2, "dist": 84.822}
    shed = {"unassigned": 3, "veh": 2, "dist": 75.006}
    assert service_key(shed) > service_key(full)
    # 反证：旧的 (veh, dist) 键会把 shed 读成改善
    assert (shed["veh"], shed["dist"]) < (full["veh"], full["dist"])


def test_violation_is_paired_per_seed_not_across_seeds():
    """只有同种子才能判违背：另一个种子里有更短的解，不构成「预算变差」的证据。"""
    iid = "r_case-A-000"
    at_seed = _anytime(iid,
                       _row(iid, "haco", 1, veh=2, dist=55.0),
                       _row(iid, "haco", 2, veh=2, dist=40.0),   # 没跑 long 的种子
                       _row(iid, "long", 1, veh=2, dist=60.0))
    assert at_seed["paired_same_seed"] == 1
    assert at_seed["violations"] == 1
    assert at_seed["list"][0]["dist"] == (55.0, 60.0)
    assert at_seed["list"][0]["dim"] == "distance"

    # 反证：若按「跨种子取 min」配（旧写法），55→60 会被 40→60 冒充成改善
    cross_seed_min = min(55.0, 40.0)
    assert cross_seed_min < 60.0


def test_long_better_and_tie_are_not_violations():
    iid = "r_case-B-000"
    got = _anytime(iid,
                   _row(iid, "haco", 1, veh=3, dist=79.118),
                   _row(iid, "long", 1, veh=2, dist=59.679))
    assert got["violations"] == 0 and got["improvements"] == 1

    iid2 = "r_case-B-001"
    same = _row(iid2, "haco", 1, veh=2, dist=42.875)
    got2 = _anytime(iid2, same, {**same, "arm": "long",
                                "evaluations": 125_000})
    assert got2["violations"] == 0 and got2["ties"] == 1


def test_service_loss_long_arm_is_a_violation_even_when_km_drop():
    """r_deadline-L-005 实测形状：多 5 倍预算反而多舍一单。"""
    iid = "r_case-C-000"
    got = _anytime(iid,
                   _row(iid, "haco", 1, veh=2, dist=84.822, unassigned=2),
                   _row(iid, "long", 1, veh=2, dist=75.006, unassigned=3))
    assert got["violations"] == 1
    assert got["list"][0]["dim"] == "unassigned"
    # 里程口径这时反而报「headroom 12%」——两个读数必须同时存在才不会骗人
    assert got["list"][0]["delta_km"] == -9.816


def test_vehicle_loss_is_a_violation_even_if_km_improves():
    iid = "r_case-D-000"
    got = _anytime(iid,
                   _row(iid, "haco", 1, veh=2, dist=80.0),
                   _row(iid, "long", 1, veh=3, dist=75.0))
    assert got["violations"] == 1 and got["list"][0]["dim"] == "vehicle"


def test_mileage_within_noise_floor_is_not_a_violation():
    """同一条路线、只差浮点尾数（d15 的 54.636→54.637 / 95.855→95.859）不算违背。"""
    iid = "r_case-E-000"
    got = _anytime(iid,
                   _row(iid, "haco", 1, veh=3, dist=54.636),
                   _row(iid, "long", 1, veh=3, dist=54.637))
    assert got["violations"] == 0 and got["ties"] == 1

    # 反证：真变差 0.867km 必须红
    iid2 = "r_case-E-001"
    got2 = _anytime(iid2,
                    _row(iid2, "haco", 1, veh=2, dist=59.346),
                    _row(iid2, "long", 1, veh=2, dist=59.346 + ANYTIME_NOISE_KM + 0.862))
    assert got2["violations"] == 1 and got2["list"][0]["dim"] == "distance"


def test_exported_objective_key_governs_before_mileage():
    """r_vrp-L-005 实测形状：两臂 un/veh 相同，long 少绕行（2.893→1.304）但多 0.874km。

    业务键里 cargo_detour 排在里程之前 ⇒ 这是改善，不是违背。
    反证就在同一例里：只看 (veh, dist) 的旧键会把 dist 变大判成违背。
    """
    iid = "r_case-H-000"
    # key = (infeasibility, vehicle_count, passenger_impact, cargo_detour, distance, duration)
    a = _row(iid, "haco", 1, veh=3, dist=106.043)
    a["objective_key"] = [0.0, 3, 0.0, 2.893, 106.043, 5000.0]
    b = _row(iid, "long", 1, veh=3, dist=106.917)
    b["objective_key"] = [0.0, 3, 0.0, 1.304, 106.917, 5100.0]
    got = _anytime(iid, a, b)
    assert got["violations"] == 0 and got["improvements"] == 1

    # 反向：绕行变多、里程变短 → 按业务键就是违背，必须报出来
    a2 = _row(iid, "haco", 1, veh=3, dist=106.917)
    a2["objective_key"] = [0.0, 3, 0.0, 1.304, 106.917, 5000.0]
    b2 = _row(iid, "long", 1, veh=3, dist=106.043)
    b2["objective_key"] = [0.0, 3, 0.0, 2.893, 106.043, 5100.0]
    got2 = _anytime(iid, a2, b2)
    assert got2["violations"] == 1
    assert got2["list"][0]["dim"] == "cargo_detour"
    assert got2["list"][0]["delta_km"] < 0


def test_no_long_arm_rows_yields_zero_pairs():
    """没跑 long 臂的轮次不许凭空产生违背读数。"""
    iid = "r_case-F-000"
    got = _anytime(iid, _row(iid, "haco", 1, veh=2, dist=50.0))
    assert got == {"paired_same_seed": 0, "ties": 0, "improvements": 0,
                   "violations": 0, "by_dim": {}, "worst_delta_km": 0.0, "list": []}


def test_headroom_readout_survives_the_classification_branches():
    """违背判据不能顺手关掉 headroom 读数。

    `mean_budget_deficit_pct`（同车数下 haco/long−1，正数=还有余量）曾经被我写在
    分类的 continue 之后 ⇒ 改善/持平那两类根本走不到它，实测整个字段直接变 None。
    两个读数方向相反（余量=long 更好，违背=long 更差），只留一个就能骗人。
    """
    iid = "r_case-G-000"
    s = aggregate([_inst(iid)], [
        _row(iid, "haco", 1, veh=3, dist=58.496),
        _row(iid, "long", 1, veh=3, dist=57.739),   # 改善分支：headroom = +1.31%
        _row(iid, "haco", 2, veh=2, dist=60.0),
        _row(iid, "long", 2, veh=2, dist=61.0),     # 违背分支：headroom 为负，不计入
    ])
    assert s["anytime"]["paired_same_seed"] == 2
    assert s["anytime"]["violations"] == 1
    assert s["serve"]["mean_budget_deficit_pct"] == pytest.approx(1.31)
    assert s["per_instance"][iid]["haco_seeds"] == "2/2"
