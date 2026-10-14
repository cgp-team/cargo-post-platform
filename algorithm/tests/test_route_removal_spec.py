"""ALNS 的 route-removal 算子：字典序目标下"关掉一台车"必须能一步做到。

背景（台账 §9.16 实测）：候选车从 3 台放到 12 台，解反而从 58.351km 变差到 76.401km、
用车从 3 台变 4 台。根因不是插入质量，而是**动作集里没有任何"整条路线撤掉"的动作**：
`ObjectiveVector.key()` 按字典序比、vehicle_count 排在里程之前
（`app/haco/encoding.py:135-157`），而既有 destroy 全是任务级的
（`random / worst / shaw / segment`）—— 要搬空一台装了 2 件以上任务的车，
第一件搬走时"里程变差而用车数没降"，字典序下必被拒，于是"那台车清空"这个状态走不到。

这里钉三件事：
1. `destroy_route` 撤**装载最少**的那台（搬运成本最低），且必须把它的任务一个不漏交出来；
2. 只剩一台在用 / 全空时它必须**什么都不做**（不许制造无意义的空撤）；
3. 修复阶段看不到被撤的那台车 —— 否则任务的最佳插入位置往往还是它自己，算子等于白做。

端到端收益不在本文件证明，由 `tests/test_fleet_scaling.py`（真实语料锚点）与度量台 d11
负责：那两条 xfail 必须转成 XPASS，且不许靠丢单换里程。
"""

from __future__ import annotations

import random
from datetime import datetime, timedelta, timezone

from app.distance import haversine_km
from app.haco.alns_v14 import destroy_route, repair_greedy
from app.haco.encoding import TaskBlock, TaskType
from app.haco.evaluator import evaluate_route_states
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.route_genome import RouteGenome
from app.models import (
    AlgorithmConfig, AlgorithmMode, OrderType, PlanOrder, PlanRequest, Station, Vehicle,
)
from app.solver import solve

TZ = timezone(timedelta(hours=8))
DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId=f"S{i}", longitude=104.000 + i * 0.01, latitude=30.000 + i * 0.01)
    for i in range(1, 11)
]
STATION_MAP = {s.stationId: s for s in STATIONS}
STATION_MAP["S0"] = DEPOT
def _pair(a: Station, b: Station) -> tuple:
    """矩阵口径与项目一致：{(起点, 终点): (公里, 秒)}，车速 25 km/h。"""
    if a.stationId == b.stationId:
        return (0.0, 0.0)
    km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
    return (round(km, 4), round(km / 25 * 3600, 1))


POINTS = STATIONS + [DEPOT]
MATRIX = {(a.stationId, b.stationId): _pair(a, b) for a in POINTS for b in POINTS}


def _task(idx: int, pickup: str, delivery: str) -> TaskBlock:
    return TaskBlock(
        task_id=f"T{idx}", task_type=TaskType.PASSENGER,
        pickup_station=pickup, delivery_station=delivery,
        size=1, order_ids=[f"P{idx}"],
    )


def _genome(index: int) -> RouteGenome:
    return RouteGenome(vehicle_index=index, vehicle_id=1000 + index, depot_station="S0")


def _loaded_genome(index: int, tasks: list[TaskBlock]) -> RouteGenome:
    """任务逐个插进同一台车（路线是否"在用"由 placements 决定）。"""
    route = _genome(index)
    pos = 1
    for task in tasks:
        route.insert_task(task, pos, pos + 1)
        pos += 2
    return route


# ─── 1. 撤哪一台、撤出什么 ──────────────────────────────────────

def test_destroy_route_empties_the_lightest_loaded_vehicle_and_returns_all_its_tasks():
    t1, t2 = _task(1, "S1", "S2"), _task(2, "S3", "S4")
    t3, t4, t5 = _task(3, "S5", "S6"), _task(4, "S7", "S8"), _task(5, "S9", "S10")
    routes = [_loaded_genome(0, [t3, t4, t5]), _loaded_genome(1, [t1, t2])]
    tasks_by_id = {t.task_id: t for t in (t1, t2, t3, t4, t5)}

    new_routes, removed, victim_index = destroy_route(routes, tasks_by_id, random.Random(7))

    assert victim_index == 1, f"该撤装载最少的那台（2 件），实际撤了 {victim_index}"
    assert {t.task_id for t in removed} == {"T1", "T2"}, \
        f"撤出的必须恰好是被关那台车的全部任务：{[t.task_id for t in removed]}"
    victim = next(r for r in new_routes if r.vehicle_index == victim_index)
    assert victim.task_count() == 0, "被撤的车必须真的清空"
    assert sum(r.task_count() for r in new_routes if r.vehicle_index != victim_index) == 3, \
        "别台车的任务一个都不许动"
    # 清空的是任务，不是路线本身：骨架与起终点事件保持完整
    assert [e.event_type.name for e in victim.events] == ["DEPOT", "RETURN"], victim.events
    # 原对象不许被就地改坏（destroy 拿到的是本轮状态的引用）
    assert routes[1].task_count() == 2, "destroy_route 必须只改副本"


def test_destroy_route_does_nothing_until_two_vehicles_are_loaded():
    """只有一台在用（或全空）时没有"关一台"的余地 —— 必须原样交回，不做无意义的空撤。"""
    a, b = _task(1, "S1", "S2"), _task(2, "S3", "S4")
    solo = [_loaded_genome(0, [a, b]), _genome(1)]
    tasks_by_id = {a.task_id: a, b.task_id: b}

    routes, removed, victim_index = destroy_route(solo, tasks_by_id, random.Random(3))

    assert (victim_index, removed) == (None, [])
    assert sum(r.task_count() for r in routes) == 2, "no-op 时不许少任务"

    empty = [_genome(0), _genome(1)]
    routes, removed, victim_index = destroy_route(empty, {}, random.Random(3))
    assert (victim_index, removed, sum(r.task_count() for r in routes)) == (None, [], 0)


def test_destroy_route_is_deterministic_for_a_given_seed():
    """确定性尺子的前提：新算子按 rng 抽样，同种子必须撤同一台车。"""
    t1, t2 = _task(1, "S1", "S2"), _task(2, "S3", "S4")
    routes = [_loaded_genome(0, [t1]), _loaded_genome(1, [t2])]
    tasks_by_id = {t.task_id: t for t in (t1, t2)}

    first = destroy_route(routes, tasks_by_id, random.Random(11))
    second = destroy_route(routes, tasks_by_id, random.Random(11))

    assert first[2] == second[2]
    assert [t.task_id for t in first[1]] == [t.task_id for t in second[1]]


# ─── 2. 修复阶段看不到被关的那台车 ────────────────────────────

def test_repair_consolidates_onto_the_remaining_vehicle_and_still_counts_one_bus():
    """算子有效的前提：把被关的车从修复候选里剔掉，任务只能往剩下的车里合。

    留着它，第一件搬回去几乎不增加绕行（最佳位置常常还是它自己），
    destroy+repair 转一圈回到原状，字典序下什么都不会发生。
    """
    t1, t2, keep = _task(1, "S1", "S2"), _task(2, "S3", "S4"), _task(9, "S5", "S6")
    routes = [_loaded_genome(0, [keep]), _loaded_genome(1, [t1, t2])]
    tasks_by_id = {t.task_id: t for t in (t1, t2, keep)}
    engine = FeasibilityEngine(station_map=STATION_MAP, matrix=MATRIX)

    destroyed, removed, victim_index = destroy_route(routes, tasks_by_id, random.Random(5))
    repair_input = [r for r in destroyed if r.vehicle_index != victim_index]
    repaired = repair_greedy(
        repair_input, removed, tasks_by_id, engine, STATION_MAP, MATRIX,
        {0: 10, 1: 10}, {0: 10, 1: 10}, {0: 0, 1: 0}, {0: 0, 1: 0},
        random.Random(5),
    )

    assert all(r.vehicle_index != victim_index for r in repaired), "修复输入里不该再有那台车"
    assert sum(r.task_count() for r in repaired) == 3, \
        f"三件任务必须全部插回去：{[(r.vehicle_index, sorted(r.placements)) for r in repaired]}"
    closed = [r for r in destroyed if r.vehicle_index == victim_index]
    whole = sorted([*repaired, *closed], key=lambda r: r.vehicle_index)
    obj = evaluate_route_states(whole, tasks_by_id, STATION_MAP, MATRIX,
                                initial_passenger_loads={0: 0, 1: 0})
    assert obj.infeasibility == 0.0, f"合车后的方案必须仍然可行：{obj}"
    assert obj.vehicle_count == 1, f"用车数应当从 2 降到 1，实际 {obj.vehicle_count}"


# ─── 3. 压实阶段的合并函数本体（台账 §14.2 的两个坑都在这里钉住）────


def _consolidate(routes, target, tasks_by_id, caps_pax):
    from app.haco.v14_solver import _consolidate_into

    tasks = list(tasks_by_id.values())
    caps = {r.vehicle_index: caps_pax for r in routes}
    zero = {r.vehicle_index: 0 for r in routes}
    engine = FeasibilityEngine(station_map=STATION_MAP, matrix=MATRIX)
    return _consolidate_into(
        routes, target, tasks, tasks_by_id, engine, STATION_MAP, MATRIX,
        caps, caps, zero, zero, None,
    )


def test_consolidate_into_closes_a_vehicle_and_keeps_every_task():
    t1, t2, keep = _task(1, "S1", "S2"), _task(2, "S3", "S4"), _task(9, "S5", "S6")
    tasks_by_id = {t.task_id: t for t in (t1, t2, keep)}
    routes = [_loaded_genome(0, [keep]), _loaded_genome(1, [t1, t2])]

    out = _consolidate(routes, 1, tasks_by_id, 10)

    assert out is not None, "全部任务放得下时合并必须给出结果"
    assert sum(r.task_count() for r in out) == 3, "任务一个都不许丢"
    assert sum(1 for r in out if r.task_count() > 0) == 1, "用车数必须真的收到 1 台"


def test_consolidate_into_never_moves_tasks_into_an_idle_candidate():
    """负控（我第一版就是栽在这）：合并时必须把任务搬进**当前在用**的车。

    允许往本来空着的候选车里插，"撤车"就变成"挪车" —— 每步都被判成功、
    用车数一动不动，实测空转到压实预算超时才整段丢弃（台账 §14.2）。
    """
    t1, t2, keep = _task(1, "S1", "S2"), _task(2, "S3", "S4"), _task(9, "S5", "S6")
    tasks_by_id = {t.task_id: t for t in (t1, t2, keep)}
    routes = [_loaded_genome(0, [keep]), _loaded_genome(1, [t1, t2]), _genome(2)]

    out = _consolidate(routes, 1, tasks_by_id, 10)

    assert out is not None
    idle = [r for r in out if r.vehicle_index == 2]
    assert idle and idle[0].task_count() == 0, \
        "任务被搬进了本来空着的候选车：这是在挪车不是在撤车"
    assert sum(1 for r in out if r.task_count() > 0) == 1


def test_consolidate_into_returns_partial_progress_when_target_is_unreachable():
    """只能关掉一台就交回那一台：进步不许因为"没走到目标"被整段丢掉。

    夹具用**货运件数**而不是乘客：载客是"同时在车"口径，任务相邻插入时永远只占 1 座，
    4 座根本不咬（我第一次写这条就是这么写空的）；而货舱按每向**累计**判（引擎既有口径），
    所以 cap=4 时 A(3)+C(2) 关得动 B、关不动 A ⇒ 目标 1 台不可达，
    必须返回"已收到 2 台"的部分结果而不是 None。
    """
    def cargo(idx: int) -> TaskBlock:
        return TaskBlock(
            task_id=f"C{idx}", task_type=TaskType.DELIVERY,
            pickup_station=f"S{idx}", delivery_station=f"S{idx}",
            size=1, order_ids=[f"D{idx}"],
        )

    def load_cargo(index: int, ids: list[int]) -> RouteGenome:
        route = _genome(index)
        pos = 1
        for i in ids:
            route.insert_task(cargo(i), pos)   # 单向派送：只有一个作业点
            pos += 1
        return route

    groups = {0: [1, 2, 3], 1: [4, 5], 2: [6, 7]}
    tasks_by_id = {f"C{i}": cargo(i) for group in groups.values() for i in group}
    routes = [load_cargo(index, ids) for index, ids in groups.items()]

    out = _consolidate(routes, 1, tasks_by_id, 4)

    assert out is not None, "已经关掉一台却整段丢弃 —— 部分进步也要交回去"
    assert sum(r.task_count() for r in out) == 7, "不许靠丢任务凑成更少车"
    assert sum(1 for r in out if r.task_count() > 0) == 2


# ─── 4. 接进求解链路后的不变量 ────────────────────────────────

def _request(passengers, vehicles, seed):
    return PlanRequest(
        requestId="route-removal", batchStart=datetime(2026, 8, 23, 8, 0, tzinfo=TZ),
        batchEnd=datetime(2026, 8, 23, 18, 0, tzinfo=TZ),
        depot=DEPOT, stations=STATIONS + [DEPOT], vehicles=vehicles,
        orders=passengers, shipments=[],
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=seed),
    )


def _passengers(count: int, span: int) -> list[PlanOrder]:
    return [
        PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                  boardingStationId=f"S{(i % span) + 1}",
                  alightingStationId=f"S{(i % span) + 3}")
        for i in range(count)
    ]


def test_solve_keeps_every_passenger_with_the_new_operator():
    """新算子只能让方案变好，不许靠丢乘客或造非法路线变好（乘客是业务底线）。"""
    passengers = _passengers(6, 8)
    vehicles = [Vehicle(vehicleId=1000 + i, passengerCapacity=8, cargoCapacity=4)
                for i in range(3)]

    out = solve(_request(passengers, vehicles, seed=5), MATRIX)

    assert out.status == "feasible", f"6 名乘客 3 台车不可能无解：{out.reason_code} {out.warnings}"
    served = {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}
    assert served == {o.orderId for o in passengers}, \
        f"不许丢乘客：缺 {sorted({o.orderId for o in passengers} - served)}"
    for plan in out.vehicle_plans:
        actions = [s.action.value for s in plan.stops]
        assert actions[0] == "DEPART" and actions[-1] == "RETURN", actions
        assert len(actions) >= 4, f"空车不该被派出去：{actions}"


def test_solve_is_still_deterministic_with_the_new_operator():
    """同种子同输入必须同解 —— 新算子按 rng 抽样，不能把确定性打破。"""
    passengers = _passengers(8, 6)
    vehicles = [Vehicle(vehicleId=1000 + i, passengerCapacity=6, cargoCapacity=4)
                for i in range(3)]
    req = _request(passengers, vehicles, seed=17)

    def key(out):
        return (out.status, len(out.vehicle_plans), round(float(out.total_distance or 0.0), 6),
                sorted(s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId))

    first, second = key(solve(req, MATRIX)), key(solve(req, MATRIX))
    assert first == second, f"同种子同输入不同解：{first} vs {second}"
