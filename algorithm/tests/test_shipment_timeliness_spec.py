"""逐单时效（readyTime / pickupDeadline / deliveryDeadline / priority）必须真的约束方案。

契约出处（不是本用例自造的字段）：
- `app/models.py:157-161` `PlanShipment.readyTime/pickupDeadline/deliveryDeadline/priority`
  注释写明「DISPATCH_CORE_V047 (section 15): 真正的订单时效表达，全部 optional」；
- `docs/dispatch/DISPATCH_V047_REPORT.md:96` 规定的语义是
  「候选级计算 estimated_pickup_eta / estimated_delivery_eta；
   delivery_eta > deadline → 候选 ETA_MISSED（INFEASIBLE），不会继续作为正常候选」。

现状（写这份用例时已核实，`app/haco/` 内 grep `ready_time|pickup_deadline|
delivery_deadline|priority` 命中 0）：时效四个字段从不进入 TaskBlock，
`FeasibilityEngine` 只有批级 `max_duration = batchEnd - batchStart`，
所以「承诺次日达却被排到第三天」在算法层面是不可见的。

时间口径（与 `validators.validate_time_window` / `feasibility_engine._check_duration`
同一把尺子：累计 = 各段行驶秒 + 每站服务秒，不额外发明等待成本）：
- 到达某事件 = 时钟 + 该段行驶时间；完成 = 到达 + 该动作服务时间；
- `readyTime`：货没打包好之前车不能取件，所以车必须等到 readyTime 才做 PICKUP
  （等待进入时钟，进而推后它自己的送达与后序订单）；
- 截止时刻比的是**完成时刻**（把手/取件动作本身要占时间）。

用例里的数值都可由几何直接证明（矩阵固定 25km/h → 144s/km，骨架 S1→S2→S3 每站 6km，
PASS 30s、PICKUP/DELIVER 各 60s），因此不依赖求解器策略就能断言「必然迟到」。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.distance import haversine_km
from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    RouteStop,
    Station,
    StopAction,
    Vehicle,
    VehiclePlan,
)
from app.solver import solve
from app.validators import service_duration

from benchmarks.lab.check import check_plan

LAT0, LON0 = 29.55, 106.50
KM_PER_DEG_LAT = 110.6
BASE = datetime(2026, 10, 8, 8, 0, tzinfo=timezone.utc)


def _st(sid: str, km: float, lat_off: float = 0.0) -> Station:
    return Station(stationId=sid, longitude=round(LON0 + lat_off, 6),
                   latitude=round(LAT0 + km / KM_PER_DEG_LAT, 6))


def _matrix(stations):
    """25km/h 的显式矩阵：时效证明只依赖这个已知速度，不依赖任何估算。"""
    m = {}
    for a in stations:
        for b in stations:
            if a.stationId == b.stationId:
                m[(a.stationId, b.stationId)] = (0.0, 0.0)
            else:
                km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
                m[(a.stationId, b.stationId)] = (round(km, 4), round(km / 25 * 3600, 1))
    return m


def _line_stations():
    return [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0)]


def _request(stations, vehicles, orders=(), shipments=(), window_h=6):
    return PlanRequest(
        requestId="eta-case", batchStart=BASE, batchEnd=BASE + timedelta(hours=window_h),
        depot=stations[0], stations=stations, vehicles=vehicles,
        orders=list(orders), shipments=list(shipments),
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=7),
    )


def _bus(cargo_capacity=20):
    return Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=cargo_capacity,
                   skeleton=["S1", "S2", "S3"])


def _served(out):
    return {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}


def _sh_by_id(request):
    return {s.shipmentId: s for s in request.shipments}


# ─── 独立复核：从发射的 stops 重算逐单时效 ────────────────────────

def _eta_violations(request, matrix, out) -> list[str]:
    """独立于 solver 内部引擎的时钟复核（口径见模块 docstring）。

    返回违规描述列表；空列表表示每个被服务的运单都满足 readyTime / 揽收 / 送达时效。
    """
    sh = _sh_by_id(request)
    bad: list[str] = []
    for plan in out.vehicle_plans:
        clock = BASE.timestamp()
        seen_pickup: dict[str, float] = {}
        for stop in plan.stops:
            if stop.segmentDuration:
                clock += float(stop.segmentDuration)
            # 货还没好：车必须等到 readyTime 才能取件（等待计入时钟）
            s = sh.get(stop.orderId or "")
            if stop.action == StopAction.PICKUP and s is not None and s.readyTime is not None:
                clock = max(clock, s.readyTime.timestamp())
            clock += service_duration(stop.action)
            if stop.action not in (StopAction.PICKUP, StopAction.DELIVER):
                continue
            if s is None:
                continue
            if stop.action == StopAction.PICKUP:
                seen_pickup[s.shipmentId] = clock
                if s.pickupDeadline is not None and clock > s.pickupDeadline.timestamp() + 1e-6:
                    bad.append(f"PICKUP_ETA_MISSED:{plan.vehicleId}:{s.shipmentId}"
                               f":{int(clock)}>{int(s.pickupDeadline.timestamp())}")
            else:
                if s.shipmentId not in seen_pickup:
                    bad.append(f"DELIVER_BEFORE_PICKUP:{plan.vehicleId}:{s.shipmentId}")
                if s.deliveryDeadline is not None and clock > s.deliveryDeadline.timestamp() + 1e-6:
                    bad.append(f"DELIVERY_ETA_MISSED:{plan.vehicleId}:{s.shipmentId}"
                               f":{int(clock)}>{int(s.deliveryDeadline.timestamp())}")
    return bad


def _stop(station_id, action, order_id=None):
    return RouteStop(stationId=station_id, action=action, orderId=order_id,
                     segmentDistance=0.0, segmentDuration=0.0)


# ─── 1. 送达时效：可证明必迟到的单子不得被「按时送达」──────────────

def test_impossible_delivery_deadline_is_not_silently_served():
    """骨架 S1→S2→S3 共 12km、限速 25km/h → 光行驶就 1728s，加上每站必停的服务时间
    最早也要 ~1938s 才能把件送到 S3；把送达截止压在 +20min（1200s）是**物理上带不到**的。
    算法只能：按时做不到就别接（舍弃 / 判无解），绝不能「接了但迟到」还报可行。
    """
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus()], shipments=[
        PlanShipment(shipmentId="TA", pickupStationId="S1", deliveryStationId="S3",
                     quantity=1, deliveryDeadline=BASE + timedelta(minutes=20)),
    ])
    out = solve(req, m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status == "feasible":
        assert _eta_violations(req, m, out) == [], \
            f"报告可行却存在迟到方案：{_eta_violations(req, m, out)}"
        assert "TA" not in _served(out), "可证明必迟到的运单被当成按时服务"
    else:
        assert out.reason_code in {"TIME_WINDOW_EXCEEDED", "INCOMPLETE_SOLUTION",
                                   "NO_FEASIBLE_INITIAL_SOLUTION", "ETA_MISSED_DELIVERY"}, \
            out.reason_code


def test_feasible_delivery_deadline_still_served():
    """正向对照：同样几何、送达截止放宽到 +60min（最早 ~1938s < 3600s）就必须服务。

    没有这一条，前面那条可以靠「所有带时效的单子一律拒收」骗绿。
    """
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus()], shipments=[
        PlanShipment(shipmentId="TB", pickupStationId="S1", deliveryStationId="S3",
                     quantity=1, deliveryDeadline=BASE + timedelta(minutes=60)),
    ])
    out = solve(req, m)
    assert out.status == "feasible", out.reason_code
    assert "TB" in _served(out), "时效内可完成的运单被误拒"
    assert _eta_violations(req, m, out) == []


# ─── 2. 揽收时效 ──────────────────────────────────────────────

def test_impossible_pickup_deadline_is_not_silently_served():
    """揽收点在 S3（12km 外，最早到达 ~1788s），把揽收截止压到 +10min 不可能赶上。"""
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus()], shipments=[
        PlanShipment(shipmentId="TC", pickupStationId="S3", deliveryStationId="S1",
                     quantity=1, pickupDeadline=BASE + timedelta(minutes=10),
                     deliveryDeadline=BASE + timedelta(hours=5)),
    ])
    out = solve(req, m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status == "feasible":
        assert _eta_violations(req, m, out) == []
        assert "TC" not in _served(out), "赶不上的揽收被当成按时服务"


def test_feasible_pickup_deadline_still_served():
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus()], shipments=[
        PlanShipment(shipmentId="TD", pickupStationId="S3", deliveryStationId="S1",
                     quantity=1, pickupDeadline=BASE + timedelta(hours=2),
                     deliveryDeadline=BASE + timedelta(hours=5)),
    ])
    out = solve(req, m)
    assert out.status == "feasible", out.reason_code
    assert "TD" in _served(out), "时效内可完成的揽收被误拒"
    assert _eta_violations(req, m, out) == []


# ─── 3. readyTime：货没好之前不能取，等待会推后送达 ────────────────

def test_ready_time_wait_cannot_be_ignored():
    """取件点 S2 最早 ~894s 到达，但货 +50min(3000s) 才打包好；送达截止 +55min(3300s)。
    等待后进路 864s → 最早送达完成 3000+60+864+60=3984s > 3300s，**必迟到**。
    忽略 readyTime 的算法会看到「894s 就到了」而误判可以按时完成。
    """
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus()], shipments=[
        PlanShipment(shipmentId="TE", pickupStationId="S2", deliveryStationId="S3",
                     quantity=1, readyTime=BASE + timedelta(minutes=50),
                     deliveryDeadline=BASE + timedelta(minutes=55)),
    ])
    out = solve(req, m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status == "feasible":
        assert _eta_violations(req, m, out) == []
        assert "TE" not in _served(out), "readyTime 造成的等待被忽略，迟到单被判按时"


def test_without_ready_time_the_same_order_is_servable():
    """正向对照：去掉 readyTime（其余完全相同，送达截止仍 +55min）就该服务。

    这一条把上一条的红锁在 readyTime 这一个变量上，而不是几何或截止时刻本身。
    """
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus()], shipments=[
        PlanShipment(shipmentId="TF", pickupStationId="S2", deliveryStationId="S3",
                     quantity=1, deliveryDeadline=BASE + timedelta(minutes=55)),
    ])
    out = solve(req, m)
    assert out.status == "feasible", out.reason_code
    assert "TF" in _served(out), "无 readyTime 时同一单本该按时可送却被误拒"
    assert _eta_violations(req, m, out) == []


# ─── 4. 时效不得损害乘客与既有服务 ───────────────────────────────

def test_passengers_are_never_dropped_because_of_freight_deadlines():
    """货运带不动可以舍弃，乘客一步不能让：每站必停拉客是既定动作。"""
    stations = _line_stations()
    m = _matrix(stations)
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3"),
        PlanOrder(orderId="P2", orderType=OrderType.PASSENGER,
                  boardingStationId="S2", alightingStationId="S3"),
    ]
    req = _request(stations, [_bus()], orders=orders, shipments=[
        # 明知带不动：让算法必须在「牺牲乘客」和「舍弃货运」之间选一个
        PlanShipment(shipmentId="TG", pickupStationId="S1", deliveryStationId="S3",
                     quantity=1, deliveryDeadline=BASE + timedelta(minutes=1)),
    ])
    out = solve(req, m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status != "feasible":
        pytest.fail(f"一票带不动的货运把整批判成无解（乘客因此没车可坐）："
                    f"{out.reason_code} {out.warnings}")
    served = _served(out)
    assert {"P1", "P2"} <= served, "乘客因货运时效被舍弃"
    assert "TG" not in served
    assert "TG" in set(out.unassigned_order_ids or []), "舍弃未如实申报"


# ─── 5. priority：稀缺舱位先给高优先级 ───────────────────────────

def test_priority_decides_which_freight_gets_the_scarce_slot():
    """货舱只剩 1 件，两票都合法且都按时可送 —— 唯一区别是 priority。

    低优先级故意取 ID "AAA"（规范任务序 task_id 排序时它在前），
    所以若实现只按 task_id 顺序补位，被留下的会是高优先级 ZZZ —— 这条用例就会红。
    """
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus(cargo_capacity=1)], shipments=[
        PlanShipment(shipmentId="AAA", pickupStationId="S2", deliveryStationId="S3",
                     quantity=1, priority=0),
        PlanShipment(shipmentId="ZZZ", pickupStationId="S2", deliveryStationId="S3",
                     quantity=1, priority=3),
    ])
    out = solve(req, m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    assert out.status == "feasible", \
        f"货运装不下时应舍弃交多段联运，而不是把整批（含公交班次）判无解：{out.reason_code}"
    served = _served(out)
    assert "ZZZ" in served, f"高优先级单被舍弃：served={served}"
    assert "AAA" not in served
    assert "AAA" in set(out.unassigned_order_ids or [])
    assert _eta_violations(req, m, out) == []


def test_priority_does_not_invent_capacity():
    """正向对照/防作弊：两票都装得下时，priority 不该让任何一票消失。"""
    stations = _line_stations()
    m = _matrix(stations)
    req = _request(stations, [_bus(cargo_capacity=2)], shipments=[
        PlanShipment(shipmentId="AAA", pickupStationId="S2", deliveryStationId="S3",
                     quantity=1, priority=0),
        PlanShipment(shipmentId="ZZZ", pickupStationId="S2", deliveryStationId="S3",
                     quantity=1, priority=3),
    ])
    out = solve(req, m)
    assert out.status == "feasible", out.reason_code
    assert {"AAA", "ZZZ"} <= _served(out), "容量够时 priority 误伤了服务率"


# ─── 6. 引擎级直接断言（不经过搜索，钉死 reason code）──────────────

def test_solvable_batch_is_not_declared_infeasible_by_seed_starvation():
    """逐单时效收紧合法插入位后，「本来可解」的批次不许被判 NO_FEASIBLE_INITIAL_SOLUTION。

    这条钉的是预算分配而不是算法质量：种子阶段只占 20% 份额，时效一紧就可能在份额内
    建不出完整初始解。先用满力构造证明解存在，再用极小份额求解，
    要求出口只能是「可行」或「如实舍弃」，不能是「无解」。
    """
    import app.haco.v14_solver as V
    from app.haco.config import HacoConfig
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import RouteGenome

    stations = [_st(f"S{k}", 3.0 * (k - 1)) for k in range(1, 13)]
    m = _matrix(stations)
    smap = {s.stationId: s for s in stations}
    veh = Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=30,
                  skeleton=[s.stationId for s in stations])
    # 承诺时刻按「这班车按骨架每站必停开到送货站」的真实耗时算，再乘 1.3 给余量：
    #   行驶 (p-1)×432s + 每站必停 30s×p + 6 票揽收 60s×6 + 本票派送 60s
    # 紧到必须按序插入才赶得上，但又确实可 satisfy —— 这正是触发种子饥饿的形态。
    def _promise(k: int) -> timedelta:
        p = k + 5                      # 送货站 S{k+5} 在骨架里的位置（1 基）
        sec = (p - 1) * 432 + 30 * p + 360 + 60
        return timedelta(seconds=sec * 1.3)

    req = _request(stations, [veh], shipments=[
        PlanShipment(shipmentId=f"T{k}", pickupStationId=f"S{k}",
                     deliveryStationId=f"S{k + 5}", quantity=1,
                     deliveryDeadline=BASE + _promise(k))
        for k in range(1, 7)
    ])

    # 1) 满力构造：证明「完整且按时」的解存在
    tasks = sorted(V._encode_tasks(req), key=V._canonical_order)
    tpl = [RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                       skeleton=[s.stationId for s in stations])]
    engine = FeasibilityEngine(
        station_map=smap, matrix=m,
        max_duration=float((req.batchEnd - req.batchStart).total_seconds()),
        base_time_s=req.batchStart.timestamp(),
    )
    from app.haco.deadline import SearchDeadline
    full = V._cheapest_insertion(
        tasks, tpl, {t.task_id: t for t in tasks}, engine,
        {0: 8}, {0: 30}, {0: 0}, {0: 0}, smap, m,
        SearchDeadline.from_seconds(60.0),
    )
    assert full.complete, "满力构造都建不出完整解，这条用例失去前提"

    # 2) 极小评估份额求解：判据只许落在「可行」或「如实舍弃」
    cfg = HacoConfig.from_algorithm_config(req.algorithmConfig)
    cfg.max_evaluations = 300
    cfg.haco_time_limit = 30.0
    cfg.overall_time_limit = 30.0
    out = V.solve(req, m, cfg)
    assert out.reason_code != "NO_FEASIBLE_INITIAL_SOLUTION", (
        f"满力可解的批次被当成无解（份额/预算问题冒充可行性问题）：{out.warnings}"
    )
    if out.status == "feasible":
        assert _eta_violations(req, m, out) == [], "为了摆脱无解交出了迟到方案"
        served = _served(out)
        undelivered = {s.shipmentId for s in req.shipments} - served
        assert undelivered <= set(out.unassigned_order_ids or []), "舍弃未如实申报"
    else:
        assert out.reason_code in {"TIME_WINDOW_EXCEEDED", "OVER_CAPACITY"}, out.reason_code


def test_loose_deadlines_do_not_shrink_the_candidate_set():
    """负控（防过度剪枝）：承诺时刻给得宽时，剪枝后的候选集必须和完全不剪枝一样。

    构造阶段的时效剪枝是在 Stage A 上界上做的「乐观下界」判断，
    一旦写错就会把**本来可行**的插入位剪掉 —— 表现不是报错，而是悄悄少服务、
    里程变差。所以这里用「同几何、同任务，只改承诺松紧」做等价对照：
    松承诺下两个引擎（有时钟 / 无时钟）给出的候选位必须逐个相同。
    """
    from app.haco.config import HacoConfig
    from app.haco.construction import generate_insertion_candidates
    from app.haco.encoding import TaskType
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import RouteGenome

    stations = _line_stations()
    m = _matrix(stations)
    smap = {s.stationId: s for s in stations}
    route = RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                        skeleton=["S1", "S2", "S3"])
    window = 6 * 3600.0

    def _cands(base_time_s):
        eng = FeasibilityEngine(station_map=smap, matrix=m,
                                max_duration=window, base_time_s=base_time_s)
        task = _task_with_eta("S:GEN", "S1", "S3", ready=None, pk_dl=None, dl_dl=None)
        return generate_insertion_candidates(
            task, [route], {task.task_id: task}, eng, smap, m,
            {0: 8}, {0: 20}, {0: 0}, {0: 0}, candidate_size=10000,
        )

    no_eta = _cands(None)
    with_clock_but_loose = _cands(BASE.timestamp())
    assert no_eta, "对照本身没有候选，用例空转"
    assert {(c.vehicle_index, c.pickup_index, c.delivery_index) for c in no_eta} == \
        {(c.vehicle_index, c.pickup_index, c.delivery_index) for c in with_clock_but_loose}, \
        "有时钟引擎在无承诺时刻时少给了候选位（剪枝漏进了不该管的分支）"


def test_prune_does_not_hide_an_on_time_slot():
    """负控（防过度剪枝 · 紧承诺侧）：按时可达的插入位不许被剪掉。

    几何同上一条，但给一个「只有靠前的插入位才赶得上」的送达承诺：
    断言候选集非空、且其中至少一个位置插进去后引擎判可行（不是全部被剪光）。
    """
    from app.haco.construction import generate_insertion_candidates
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import RouteGenome

    stations = _line_stations()
    m = _matrix(stations)
    smap = {s.stationId: s for s in stations}
    route = RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                        skeleton=["S1", "S2", "S3"])
    # 送达完成最早约 1938s（S1 揽收 → 依次停 S2/S3）；给到 +40min（2400s）= 可达但紧
    eng = FeasibilityEngine(station_map=smap, matrix=m, max_duration=6 * 3600.0,
                            base_time_s=BASE.timestamp())
    task = _task_with_eta("S:PRN", "S1", "S3", ready=None, pk_dl=None,
                          dl_dl=(BASE + timedelta(minutes=40)).timestamp())
    cands = generate_insertion_candidates(
        task, [route], {task.task_id: task}, eng, smap, m,
        {0: 8}, {0: 20}, {0: 0}, {0: 0}, candidate_size=10000,
    )
    assert cands, "按时可达的插入位被剪光了（过度剪枝 = 悄悄少服务）"

    # 反向：压到 +20min 时这个任务没有任何按时位，候选必须为空（剪枝真的在工作）
    task_late = _task_with_eta("S:PRN", "S1", "S3", ready=None, pk_dl=None,
                               dl_dl=(BASE + timedelta(minutes=20)).timestamp())
    assert not generate_insertion_candidates(
        task_late, [route], {task_late.task_id: task_late}, eng, smap, m,
        {0: 8}, {0: 20}, {0: 0}, {0: 0}, candidate_size=10000,
    ), "必迟到的插入位仍然进了候选（剪枝没生效，白烧预算）"


def test_tail_slot_before_return_is_not_pruned_by_the_return_leg():
    """专门钉「插到 RETURN 之前」这一类位：下界的前驱不能取 RETURN 本身。

    这条是回归锚：错过一次就把 r_vrp/S 里程从 5.99 抬到 9.01、
    r_village/M 从 22.4 抬到 38.6 —— 表现不是报错，而是悄悄绕远。
    事件序 DEPOT(S1) · PASS S1 · PASS S2 · PASS S3 · RETURN(S1)，
    揽收落在 S3、派送落在 S3 旁 1km 的 V：派送位只能是尾部（RETURN 之前）。
    真实到达约 2082s（相对发车），给到 +36min（2160s）本该有位；
    若把 RETURN 当会前驱，下界会算成 3606+144+60=3810s → 尾部位被整段回程误剪。
    """
    from app.haco.construction import generate_insertion_candidates
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import RouteGenome

    stations = _line_stations() + [_st("V", 13.0, lat_off=0.0)]
    m = _matrix(stations)
    smap = {s.stationId: s for s in stations}
    route = RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                        skeleton=["S1", "S2", "S3"])
    eng = FeasibilityEngine(station_map=smap, matrix=m, max_duration=6 * 3600.0,
                            base_time_s=BASE.timestamp())
    task = _task_with_eta("S:TAIL", "S3", "V", ready=None, pk_dl=None,
                          dl_dl=(BASE + timedelta(minutes=36)).timestamp())
    cands = generate_insertion_candidates(
        task, [route], {task.task_id: task}, eng, smap, m,
        {0: 8}, {0: 20}, {0: 0}, {0: 0}, candidate_size=10000,
    )
    assert cands, "尾部（RETURN 之前）的按时可插位被误剪光了"
    assert any(c.delivery_index == len(route.events) for c in cands), \
        f"候选里不含尾部插入位，说明前驱又取到 RETURN：{[(c.pickup_index, c.delivery_index) for c in cands]}"

    # 反向对照：把承诺压到物理上不可能（+20min < 2082s）时，尾部位必须被剪空
    task_late = _task_with_eta("S:TAIL", "S3", "V", ready=None, pk_dl=None,
                               dl_dl=(BASE + timedelta(minutes=20)).timestamp())
    assert not generate_insertion_candidates(
        task_late, [route], {task_late.task_id: task_late}, eng, smap, m,
        {0: 8}, {0: 20}, {0: 0}, {0: 0}, candidate_size=10000,
    ), "必迟到的尾部位仍在候选里（剪枝失效，白烧预算）"


def _task_with_eta(tid, pk, dl, ready, pk_dl, dl_dl):
    from app.haco.encoding import TaskBlock, TaskType
    return TaskBlock(
        task_id=tid, task_type=TaskType.SHIPMENT,
        pickup_station=pk, delivery_station=dl, size=1, order_ids=[tid[2:]],
        ready_time_s=ready, pickup_deadline_s=pk_dl, delivery_deadline_s=dl_dl,
    )


def test_engine_rejects_route_that_misses_delivery_deadline():
    from app.haco.encoding import TaskBlock, TaskType
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import EventType, RouteEvent, RouteGenome

    stations = _line_stations()
    m = _matrix(stations)
    station_map = {s.stationId: s for s in stations}
    task = TaskBlock(
        task_id="S:TH", task_type=TaskType.SHIPMENT,
        pickup_station="S1", delivery_station="S3", size=1, order_ids=["TH"],
        delivery_deadline_s=(BASE + timedelta(minutes=20)).timestamp(),
    )
    route = RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                        skeleton=["S1", "S2", "S3"])
    route.insert_task(task, 2, 4)   # 在 PASS S1 之后揽收、PASS S3 附近派送
    engine = FeasibilityEngine(station_map=station_map, matrix=m,
                               max_duration=6 * 3600, base_time_s=BASE.timestamp())
    res = engine.check(route, {"S:TH": task}, passenger_capacity=8, cargo_capacity=20)
    assert not res.feasible, "引擎对必迟到的送达视而不见"
    assert res.reason_code in {"ETA_MISSED_DELIVERY", "DELIVERY_ETA_MISSED"}, res.reason_code


def test_engine_accepts_route_within_all_deadlines():
    """同一几何只放宽截止：引擎必须判可行（防「一律拒绝」）。"""
    from app.haco.encoding import TaskBlock, TaskType
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import RouteGenome

    stations = _line_stations()
    m = _matrix(stations)
    station_map = {s.stationId: s for s in stations}
    task = TaskBlock(
        task_id="S:TI", task_type=TaskType.SHIPMENT,
        pickup_station="S1", delivery_station="S3", size=1, order_ids=["TI"],
        ready_time_s=BASE.timestamp(),
        pickup_deadline_s=(BASE + timedelta(hours=1)).timestamp(),
        delivery_deadline_s=(BASE + timedelta(hours=2)).timestamp(),
    )
    route = RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                        skeleton=["S1", "S2", "S3"])
    route.insert_task(task, 2, 4)
    engine = FeasibilityEngine(station_map=station_map, matrix=m,
                               max_duration=6 * 3600, base_time_s=BASE.timestamp())
    res = engine.check(route, {"S:TI": task}, passenger_capacity=8, cargo_capacity=20)
    assert res.feasible, f"时效内却被引擎拒判：{res.reason_code}"


def test_engine_is_inert_without_timeliness_fields():
    """向后兼容：字段全缺省时行为必须与旧版完全一致（不凭空造时效）。"""
    from app.haco.encoding import TaskBlock, TaskType
    from app.haco.feasibility_engine import FeasibilityEngine
    from app.haco.route_genome import RouteGenome

    stations = _line_stations()
    m = _matrix(stations)
    station_map = {s.stationId: s for s in stations}
    task = TaskBlock(task_id="S:TJ", task_type=TaskType.SHIPMENT,
                     pickup_station="S1", delivery_station="S3", size=1, order_ids=["TJ"])
    route = RouteGenome(vehicle_index=0, vehicle_id=1000, depot_station="S1",
                        skeleton=["S1", "S2", "S3"])
    route.insert_task(task, 2, 4)
    engine = FeasibilityEngine(station_map=station_map, matrix=m, max_duration=6 * 3600)
    assert engine.check(route, {"S:TJ": task}, 8, 20).feasible


# ─── 7. 度量台负控：独立校验器必须能抓到迟到方案 ─────────────────

def test_lab_checker_detects_late_delivery():
    """负控（配对）：同一份真方案，只把承诺时刻收紧 → check_plan 必须报 ETA 违规。

    用真实 solver 输出而不是手搓 stops，是为了让「只有时效这一维变了」成立：
    手搓方案会顺带踩响骨架/覆盖之类的判据，绿灯就测不到时效判据本身。
    """
    stations = _line_stations()
    m = _matrix(stations)
    veh = [_bus()]
    generous = _request(stations, veh, shipments=[
        PlanShipment(shipmentId="TK", pickupStationId="S1", deliveryStationId="S3",
                     quantity=1, deliveryDeadline=BASE + timedelta(minutes=60)),
    ])
    out = solve(generous, m)
    assert out.status == "feasible" and "TK" in _served(out), "对照组本身没服务"

    # 同一份方案、同一几何，只把送达承诺压到物理上不可能兑现的 +20min
    tight = _request(stations, veh, shipments=[
        PlanShipment(shipmentId="TK", pickupStationId="S1", deliveryStationId="S3",
                     quantity=1, deliveryDeadline=BASE + timedelta(minutes=20)),
    ])
    assert _eta_violations(tight, m, out), "独立时钟复核失效，这条负控没有内容"
    assert not _eta_violations(generous, m, out), "对照组的独立复核本身在误报"

    errs = check_plan(tight, m, out)
    assert any("ETA" in e for e in errs), f"度量台看不见迟到方案：{errs}"
    # 正向对照：原承诺下不该报时效违规（防「一律报违规」式的假负控）
    assert not any("ETA" in e for e in check_plan(generous, m, out)), \
        check_plan(generous, m, out)
