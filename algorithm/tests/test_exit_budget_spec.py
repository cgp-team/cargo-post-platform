"""出口预算的两条不变量：耗尽时的诚实出口，以及"扫描不被盲切"这个已被实测的决定。

乘客那两条是契约级（后端 `AlgorithmResultValidator.java:113` 对"乘客被声明为未分配"
直接判非法 ⇒ 算法侧交出去的就是必然被拒的方案）。
最后一条钉的是一次**已被否掉**的做法：把 `deadline` 传进出口的候选扫描（d20 实测
41 行结果被改、penalized gap 4.641→9.796、墙钟反而更慢 ⇒ 已回滚）。
若要重新接闸，先拿出"同样预算下不改结果"的更便宜扫描，再把这条用例反过来写。
"""
from __future__ import annotations

from app.haco import v14_solver as VS
from app.haco.deadline import SearchDeadline
from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.route_genome import RouteGenome
from app.models import Station

STATIONS = {
    sid: Station(stationId=sid, longitude=float(i), latitude=0.0)
    for i, sid in enumerate(["D", "S1", "S2", "S3"])
}
MATRIX = {(a, b): (0.0 if a == b else 1.0, 0.0 if a == b else 60.0)
          for a in STATIONS for b in STATIONS}


def _delivery(tid, station, size=1):
    return TaskBlock(task_id=tid, task_type=TaskType.DELIVERY,
                     pickup_station=station, delivery_station=station,
                     size=size, order_ids=[tid])


def _route_with(task_ids):
    g = RouteGenome(vehicle_index=0, vehicle_id=1, depot_station="D",
                    skeleton=["D", "S1", "S2", "S3"])
    pos = 1
    for tid in task_ids:
        g.insert_task(_delivery(tid, "S2"), pos)
        pos += 1
    return g


ALL_TASKS = [_delivery(f"T{i}", "S2") for i in range(6)]


def _call_complete(deadline, tasks=None):
    tasks = tasks if tasks is not None else ALL_TASKS
    routes = [_route_with(["T0"])]
    tasks_by_id = {t.task_id: t for t in tasks}
    return VS._complete_or_discard(
        routes, tasks, tasks_by_id, FeasibilityEngine(),
        passenger_capacities={0: 5}, cargo_capacities={0: 50},
        initial_passenger_loads={0: 0}, initial_cargo_loads={0: 0},
        station_map=STATIONS, matrix=MATRIX,
        deadline=deadline, discard_penalty_km=6.0,
    )


def _capture_scans(monkeypatch):
    seen: list = []
    real = VS.generate_insertion_candidates

    def spy(*a, **kw):
        seen.append(kw.get("deadline", "MISSING"))
        return real(*a, **kw)

    monkeypatch.setattr(VS, "generate_insertion_candidates", spy)
    return seen


def test_exhausted_budget_never_sheds_a_passenger():
    """预算耗尽时乘客不许进 unassigned —— 后端按契约直接拒收整个方案。"""
    passenger = TaskBlock(task_id="P9", task_type=TaskType.PASSENGER,
                          pickup_station="S1", delivery_station="S3",
                          size=1, order_ids=["P9"])
    tasks = [ALL_TASKS[0], passenger]
    routes, discarded, handovers = _call_complete(
        SearchDeadline.from_seconds(0.0), tasks)
    assert routes is None, (
        f"预算耗尽时把乘客交了出去：routes={routes} "
        f"discarded={[d.task_id for d in discarded or []]}"
    )
    assert [d.task_id for d in (discarded or [])] == []


def test_exhausted_budget_still_sheds_freight():
    """正向对照（同一夹具，只把乘客换成货）：货该如实舍弃，不能变成整批判无解。

    没有这条，上一例可以靠"预算耗尽一律返回 None"骗绿 —— 那是把货运的合法出口也堵死。
    """
    freight = TaskBlock(task_id="D9", task_type=TaskType.DELIVERY,
                        pickup_station="S2", delivery_station="S2",
                        size=1, order_ids=["D9"])
    tasks = [ALL_TASKS[0], freight]
    routes, discarded, _h = _call_complete(SearchDeadline.from_seconds(0.0), tasks)
    assert routes is not None
    assert [d.task_id for d in discarded] == ["D9"]


def test_exhausted_budget_marks_freight_as_planned_nothing():
    """被舍弃的货不得同时出现在方案里（虚报服务率的形状）。"""
    routes, discarded, _h = _call_complete(SearchDeadline.from_seconds(0.0))
    assert discarded
    placed = {tid for r in (routes or []) for tid in r.placements}
    for d in discarded:
        assert d.task_id not in placed, (
            f"{d.task_id} 既在车上（会被执行）又在 unassigned 清单里"
        )


def test_exit_scan_is_not_cut_off_by_the_completion_budget(monkeypatch):
    """钉住"扫描不接出口预算"这个已被实测的决定（d20）。

    接上之后单次扫描在预算内做不完它必须做的搜索量 ⇒ 结果被改，而不是时间被省：
    25 行变差 / 16 行变好，多行在字典序更高的一维上退步（1 车→2 车、2 车→3 车），
    penalized gap 4.641→9.796，haco 墙钟总量 812.9→845.6s（更慢）。
    """
    seen = _capture_scans(monkeypatch)
    _call_complete(SearchDeadline.from_seconds(30.0))
    assert seen, "出口一次扫描都没发起，用例失去前提"
    bound = [d for d in seen if d != "MISSING"]
    assert not bound, (
        f"{len(bound)}/{len(seen)} 次出口扫描收到了 deadline ⇒ 又在盲切单次扫描了"
    )


def test_search_time_repair_scan_is_not_cut_off_either(monkeypatch):
    """搜索期的 `_repair_unassigned` 同一处置（d20 的回滚覆盖这两处）。"""
    seen = _capture_scans(monkeypatch)
    routes = [_route_with(["T0"])]
    tasks_by_id = {t.task_id: t for t in ALL_TASKS}
    VS._repair_unassigned(
        routes, ALL_TASKS, tasks_by_id, FeasibilityEngine(),
        {0: 5}, {0: 50}, {0: 0}, {0: 0}, STATIONS, MATRIX,
        SearchDeadline.from_seconds(30.0),
    )
    assert seen, "补插一次扫描都没发起，用例失去前提"
    assert all(d == "MISSING" for d in seen), (
        f"{sum(1 for d in seen if d != 'MISSING')}/{len(seen)} 次补插扫描收到了预算"
    )
