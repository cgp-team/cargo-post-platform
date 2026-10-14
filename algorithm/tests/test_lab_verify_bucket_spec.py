"""verify 桶与"崩掉的一行"：尺子既不能把崩溃读成合规，也不能被一道崩题弄死整轮。

背景（都是本轮实测出来的形状，不是设想）：线上留痕语料第一次跑（d22_online_first）时，
`run_one` 是按 instance_id 从磁盘重取题目的，而 `online-*` 没有磁盘缓存 ⇒
每行都是 `status="error", error="INSTANCE_NOT_CACHED"`（这行根本没有 `reason` 键），
于是 `aggregate` 在拼 `entry["statuses"]` 时 `KeyError: 'reason'` ——
一道题的崩升级为整轮聚合崩，唯一那份现场证据也跟着丢了。

两条判据分开钉：
1. 异常行必须显示成**读数**（`error/...`），不能是 traceback；
2. 异常行**不算合规**（旧写法 `status != "feasible"` 把它当成"状态不限里的一种状态"，
   于是一道崩题 = 一个绿格，`verify.correct_rate` 会被静默抬高）。
"""
from __future__ import annotations

from types import SimpleNamespace

from benchmarks.lab.run import _status_tag, aggregate


def _inst(iid: str, expect: str = "verify") -> SimpleNamespace:
    return SimpleNamespace(instance_id=iid, expect=expect, family="online", tier="S")


def _ok_row(iid: str) -> dict:
    return {"instance_id": iid, "arm": "haco", "seed": 1, "status": "feasible",
            "reason": None, "valid": True, "veh": 1, "dist": 10.0, "unassigned": 0,
            "wall_ms": 100.0}


def _error_row(iid: str) -> dict:
    """`run_one` 抛异常/取不到题时交回的那一行 —— 注意它没有 `reason` 键。"""
    return {"instance_id": iid, "arm": "haco", "seed": 1, "status": "error",
            "error": "KeyError:'reason'", "wall_ms": 5.0}


def test_status_tag_shows_the_crash_instead_of_raising():
    tag = _status_tag(_error_row("online-abc"))
    assert tag.startswith("error/"), tag
    assert "KeyError" in tag, "要把崩的原因显示出来，不然读数里只有一个字"
    assert _status_tag(_ok_row("online-abc")) == "feasible/None"


def test_error_row_is_not_counted_as_compliant():
    iid = "online-abc"
    summary = aggregate([_inst(iid)], [_ok_row(iid), _error_row(iid)])
    assert summary["verify"]["total"] == 1
    # verify 是"按实例记"的：这个实例里有一行没交方案 ⇒ 整个实例不合格（不是按行数比例）。
    assert summary["verify"]["correct_rate"] == 0.0, summary["verify"]
    assert summary["robustness"]["exceptions"] == 1


def test_all_error_round_reports_zero_rate_instead_of_dying():
    """这条才是真正的负控：旧写法 `r.get("status") != "feasible" or r.get("valid")`
    对 error 行直接判真 ⇒ 一整轮全崩的读数会被写成 **correct_rate 1.0**（全绿），
    而且因为 statuses 那行 KeyError，聚合还会先崩掉。两个毛病在这里一起钉住。
    """
    iid = "online-dead"
    summary = aggregate([_inst(iid)], [_error_row(iid)])
    assert summary["verify"]["correct_rate"] == 0.0
    statuses = summary["per_instance"][iid]["statuses"]
    assert statuses == ["error/KeyError:'reason'"], statuses


def test_serve_bucket_still_ignores_error_rows_as_not_served():
    # 对照组：serve 侧原本就是"没交方案=没服务"，这条不该被我这次改动带偏
    iid = "r_village-S-000"
    summary = aggregate([_inst(iid, expect="serve")], [_error_row(iid)])
    assert not summary["serve"]["feasible_any_seed"], summary["serve"]
