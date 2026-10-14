"""#10 合并验收：`_check_duration` + `_check_timeliness` 合成一次行走，判据必须一位不变。

剖面（§15.1）量到的热点是"每次候选评估都要从头走完这条路线"，而这两支检查走的是
**同一条腿序列**、把 `compute_duration` 算了两遍。合并是纯性能改动 ⇒ 验收标准就是等价：
把合并前的两支检查逐字留在本文件里当参照实现，在一组参数栅格上要求
`(feasible, reason_code)` 与新实现**完全一致**。

参照实现不是"我认为它原来是什么行为"，是从改动前的源码原样抄回来的（含两处
门控条件与先后顺序），所以如果哪天有人改口径改错了方向，这组用例会红。
"""
from __future__ import annotations

import pytest

from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine, _EVENT_TO_ACTION
from app.haco.route_genome import EventType, RouteGenome
from app.models import Station, StopAction
from app.validators import service_duration

STATIONS = {
    sid: Station(stationId=sid, longitude=0.0 + i, latitude=0.0)
    for i, sid in enumerate(["D", "S1", "S2", "S3"])
}
# 每站间 1km / 60s（与几何无关的显式矩阵，栅格结果只依赖这张表）
MATRIX = {}
for _a in STATIONS:
    for _b in STATIONS:
        MATRIX[(_a, _b)] = (0.0, 0.0) if _a == _b else (1.0, 60.0)


# ───────────────────────── 合并前的参照实现（逐字复刻） ─────────────────────────

def _legacy_check_duration(route, station_map, matrix, max_duration):
    """原 `_check_duration`：总耗时 = 各段行驶 + 每站服务。"""
    from app.haco.heuristic import compute_duration

    total = 0.0
    for event in route.events:
        total += service_duration(
            _EVENT_TO_ACTION.get(event.event_type, StopAction.PASS)
        )
    for i in range(1, len(route.events)):
        previous = route.events[i - 1]
        current = route.events[i]
        total += compute_duration(
            station_map[previous.station_id], station_map[current.station_id], matrix
        )
    if total > max_duration:
        return (False, "TIME_WINDOW_EXCEEDED")
    return (True, None)


def _legacy_check_timeliness(route, tasks_by_id, station_map, matrix,
                             base_time_s, max_duration):
    """原 `_check_timeliness`：含 readyTime 等待的逐单时效 + 批级含等待复核。"""
    from app.haco.heuristic import compute_duration

    clock = float(base_time_s)
    eps = 1e-6
    for i, event in enumerate(route.events):
        if i > 0:
            previous = route.events[i - 1]
            clock += compute_duration(
                station_map[previous.station_id], station_map[event.station_id], matrix
            )
        task = tasks_by_id.get(event.task_id) if event.task_id else None
        if task is not None and event.event_type == EventType.PICKUP:
            if task.ready_time_s is not None and clock < task.ready_time_s:
                clock = float(task.ready_time_s)
        clock += service_duration(
            _EVENT_TO_ACTION.get(event.event_type, StopAction.PASS)
        )
        if task is None:
            continue
        if (event.event_type == EventType.PICKUP
                and task.pickup_deadline_s is not None
                and clock > task.pickup_deadline_s + eps):
            return (False, "ETA_MISSED_PICKUP")
        if (event.event_type == EventType.DELIVER
                and task.delivery_deadline_s is not None
                and clock > task.delivery_deadline_s + eps):
            return (False, "ETA_MISSED_DELIVERY")
    if max_duration is not None and clock > base_time_s + max_duration + eps:
        return (False, "TIME_WINDOW_EXCEEDED")
    return (True, None)


def legacy_expect(route, tasks_by_id, max_duration, base_time_s):
    """旧 `check()` 里两支检查的门控与先后顺序，原样搬到参照实现上。"""
    if max_duration is not None:
        verdict = _legacy_check_duration(route, STATIONS, MATRIX, max_duration)
        if not verdict[0]:
            return verdict
    if base_time_s is not None:
        verdict = _legacy_check_timeliness(
            route, tasks_by_id, STATIONS, MATRIX, base_time_s, max_duration)
        if not verdict[0]:
            return verdict
    return (True, None)


# ───────────────────────── 路线样本 ─────────────────────────

def _shipment(tid, pickup, delivery, size=1, ready=None, pk_deadline=None, dl_deadline=None):
    return TaskBlock(task_id=tid, task_type=TaskType.SHIPMENT,
                     pickup_station=pickup, delivery_station=delivery, size=size,
                     order_ids=[tid], ready_time_s=ready,
                     pickup_deadline_s=pk_deadline, delivery_deadline_s=dl_deadline)


def _passenger(tid, boarding, alighting):
    return TaskBlock(task_id=tid, task_type=TaskType.PASSENGER,
                     pickup_station=boarding, delivery_station=alighting, size=1,
                     order_ids=[tid])


def _route(tasks, positions):
    g = RouteGenome(vehicle_index=0, vehicle_id=1, depot_station="D",
                    skeleton=["D", "S1", "S2", "S3"])
    for task, pos in zip(tasks, positions):
        if task.task_type == TaskType.SHIPMENT:
            g.insert_task(task, pos[0], pos[1])
        else:
            g.insert_task(task, pos[0], pos[1])
    return g


def _samples():
    """四种形状：纯货、货+客、带 readyTime 等待、截止压线。"""
    out = []

    sh = _shipment("S:T1", "S1", "S2")
    out.append(("单腿货", _route([sh], [((1, 2))]), {sh.task_id: sh}))

    sh2 = _shipment("S:T2", "S1", "S3", size=2)
    pax = _passenger("P:P1", "S1", "S2")
    out.append(("货+客", _route([sh2, pax], [(1, 3), (2, 4)]),
                {sh2.task_id: sh2, pax.task_id: pax}))

    wait = _shipment("S:T3", "S1", "S2", ready=600.0)
    out.append(("要等待", _route([wait], [(1, 2)]), {wait.task_id: wait}))

    late = _shipment("S:T4", "S1", "S2", dl_deadline=120.0)
    out.append(("迟到", _route([late], [(1, 2)]), {late.task_id: late}))

    tight_pk = _shipment("S:T5", "S1", "S2", pk_deadline=65.0)
    out.append(("取件压线", _route([tight_pk], [(1, 2)]), {tight_pk.task_id: tight_pk}))
    return out


GRID = [
    (None, None), (None, 0.0), (None, 3600.0),
    (120.0, None), (120.0, 0.0), (120.0, 3600.0),
    (300.0, None), (300.0, 0.0), (300.0, 3600.0),
    (3000.0, 0.0), (3000.0, 3600.0),
]


@pytest.mark.parametrize("name,route,tasks", _samples())
@pytest.mark.parametrize("max_duration,base_time_s", GRID)
def test_merged_check_matches_legacy(name, route, tasks, max_duration, base_time_s):
    """合并后每个 (路线形状 × 预算/时钟) 组合的判定，必须与合并前逐格一致。"""
    engine = FeasibilityEngine()
    result = engine.check(
        route, tasks,
        passenger_capacity=5, cargo_capacity=5,
        initial_passenger_load=0, initial_cargo_load=0,
        station_map=STATIONS, matrix=MATRIX,
        max_duration=max_duration, base_time_s=base_time_s,
    )
    expected = legacy_expect(route, tasks, max_duration, base_time_s)
    assert (result.feasible, result.reason_code) == expected, (
        f"{name} max_duration={max_duration} base_time_s={base_time_s}: "
        f"新实现给出 {(result.feasible, result.reason_code)}，合并前应为 {expected}"
    )


def _random_route_and_tasks(rng):
    """随机造一条路线（1~4 件任务、随机类型与时限），造不出来就返回 None。"""
    line = ["S1", "S2", "S3"]
    tasks = {}
    planned = []
    for i in range(rng.randint(1, 4)):
        kind = rng.choice(["SHIPMENT", "PASSENGER", "DELIVERY", "PICKUP"])
        a = rng.choice(line)
        b = rng.choice(line)
        tid = f"T{i}"
        ready = rng.choice([None, 0.0, 120.0, 600.0, 5000.0]) if kind == "SHIPMENT" else None
        pk = rng.choice([None, 60.0, 130.0, 600.0, 1e9]) if kind == "SHIPMENT" else None
        dl = rng.choice([None, 60.0, 200.0, 900.0, 1e9]) if kind in ("SHIPMENT", "DELIVERY") else None
        if kind == "SHIPMENT":
            t = _shipment(tid, a, b, size=rng.randint(1, 2),
                          ready=ready, pk_deadline=pk, dl_deadline=dl)
        elif kind == "PASSENGER":
            t = _passenger(tid, a, b)
        else:
            t = TaskBlock(task_id=tid, task_type=TaskType.DELIVERY if kind == "DELIVERY"
                          else TaskType.PICKUP,
                          pickup_station=a, delivery_station=b, size=1, order_ids=[tid])
        tasks[tid] = t
        planned.append(t)

    g = RouteGenome(vehicle_index=0, vehicle_id=1, depot_station="D",
                    skeleton=["D", "S1", "S2", "S3"])
    for t in planned:
        n = len(g.events)
        placed = False
        for _try in range(6):
            p = rng.randint(1, max(1, n - 1))
            try:
                if t.task_type in (TaskType.SHIPMENT, TaskType.PASSENGER):
                    d = rng.randint(p + 1, max(p + 1, n))
                    g.insert_task(t, p, d)
                else:
                    g.insert_task(t, p)
                placed = True
                break
            except (ValueError, IndexError):
                continue
        if not placed:
            return None
    return g, tasks


def test_random_battery_matches_legacy_two_walk_version():
    """随机 N 千条路线逐条比对合并版与"两支检查各走一遍"的判定（§15.2 验收①）。

    定向夹具挡不住的是"边界组合"：eps 压线、等待推后续、RETURN 前一位插入、
    无骨架纯 VRP —— 只有随机栅格能把这些撞出来。直接比对 `_check_timing` 与参照实现，
    绕开其它检查的早退，这样每条样本测的都是被改动的那段代码。
    """
    import random

    rng = random.Random(20261012)
    checked = 0
    tried = 0
    while checked < 3000 and tried < 30000:
        tried += 1
        built = _random_route_and_tasks(rng)
        if built is None:
            continue
        route, tasks = built
        max_duration = rng.choice([None, 60.0, 180.0, 400.0, 1200.0, 1e9])
        base_time_s = rng.choice([None, 0.0, 1.0, 3600.0])
        got = FeasibilityEngine._check_timing(
            route, tasks, STATIONS, MATRIX, max_duration, base_time_s)
        expected = legacy_expect(route, tasks, max_duration, base_time_s)
        assert (got.feasible, got.reason_code) == expected, (
            f"events={[e.event_type.value for e in route.events]} "
            f"tasks={sorted(tasks)} max_duration={max_duration} "
            f"base_time_s={base_time_s}: 新 {(got.feasible, got.reason_code)} "
            f"旧 {expected}"
        )
        checked += 1
    assert checked == 3000, f"只造出 {checked} 条随机路线，样本量不足以保证据"
    assert tried < 30000


def test_merged_check_actually_walks_once():
    """性能改动得证明自己真的少走了路：数同一段腿被矩阵探测的次数。

    合并前两支检查各自从头走一遍 ⇒ 每段被查 2 次；合并后 1 次。
    这条存在的原因：等价夹具只能证明"判据没变"，不能证明"省下了那一趟"。
    把两支检查恢复成重走一遍（临时锚点已实测）本用例就会红 `assert 2 == 1`。
    """
    counted = {}

    class _CountingMatrix:
        """`compute_duration` 用的是 `key in matrix` + `matrix[key]`（heuristic.py:65）。"""

        def __init__(self, inner):
            self._inner = inner

        def __contains__(self, key):
            counted[key] = counted.get(key, 0) + 1
            return key in self._inner

        def __getitem__(self, key):
            return self._inner[key]

        def get(self, key, default=None):
            return self._inner.get(key, default)

    sh = _shipment("S:T6", "S1", "S2")
    route = _route([sh], [(1, 2)])
    engine = FeasibilityEngine()
    engine.check(
        route, {sh.task_id: sh},
        passenger_capacity=5, cargo_capacity=5,
        initial_passenger_load=0, initial_cargo_load=0,
        station_map=STATIONS, matrix=_CountingMatrix(MATRIX),
        max_duration=3000.0, base_time_s=3600.0,
    )
    assert counted, "没有经过矩阵取腿时长 ⇒ 这个计数法失去前提"
    # 同一段腿被 `in` 探测的次数：合并前两支检查各走一遍 ⇒ 每段 2 次；合并后 1 次
    worst = max(counted.values())
    assert worst == 1, (
        f"同一段腿被查了 {worst} 次 ⇒ 合并没真的省掉一次整路线行走"
    )
