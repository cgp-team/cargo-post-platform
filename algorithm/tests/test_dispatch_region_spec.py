"""通用调度规格的可执行验收（对应规格 §六 回归测试 1 / 2 与 §一~§四 的不变量）。

规格核心：地理归属 ≠ 执行归属。任务点落在哪个地理区间只是**候选**，
最终由它在哪一段行程被执行（exec_region）决定，而 exec_region 由全局代价选出来；
订单不可拆分（取送成对、先取后送），但两条腿可以落在不同执行区间；
代价过高时订单**可以舍弃**（惩罚高但有限），而不是硬塞进路线绕远。

现状（写这份用例时）：
- PASS 唯一性没有守卫（validate_skeleton 只顺序匹配，重复停靠同一个站不会被判违规）；
- 没有 exec_region / geo_candidates 这两个概念，执行区间只是插入位置的副产品，
  既不能全局优化也不能留痕；
- 订单不可舍弃：_repair_unassigned/_ensure_complete 会强制补全，补不回就整批判无解。
所以这里大部分断言现在应当是红的，实现完再绿。
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
    Station,
    StopAction,
    Vehicle,
)
from app.solver import solve
from benchmarks.lab.run import _cfg

DEPOT_LAT, DEPOT_LON = 29.55, 106.50
KM_PER_DEG_LAT = 110.6


def _st(sid: str, km_from_depot: float) -> Station:
    """沿一条南北向走廊摆真实公里级的站点（1° 纬度 ≈ 110.6 km）。"""
    return Station(
        stationId=sid,
        longitude=DEPOT_LON,
        latitude=round(DEPOT_LAT + km_from_depot / KM_PER_DEG_LAT, 6),
    )


def _matrix(stations: list[Station]) -> dict:
    m = {}
    for a in stations:
        for b in stations:
            if a.stationId == b.stationId:
                m[(a.stationId, b.stationId)] = (0.0, 0.0)
                continue
            km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
            m[(a.stationId, b.stationId)] = (round(km, 4), round(km / 25.0 * 3600, 1))
    return m


def _request(stations, vehicles, orders, shipments):
    start = datetime(2026, 10, 8, 8, 0, tzinfo=timezone.utc)
    return PlanRequest(
        requestId="spec-case", batchStart=start, batchEnd=start + timedelta(hours=6),
        depot=stations[0], stations=stations, vehicles=vehicles,
        orders=orders, shipments=shipments,
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=7),
    )


def _leg_points():
    """站点 1→2→3；a、b、c 在 R12 走廊内侧，d 在 R23 走廊内侧。"""
    s1, s2, s3 = _st("S1", 0.0), _st("S2", 10.0), _st("S3", 20.0)
    a, b, c = _st("A", 2.0), _st("B", 4.0), _st("C", 8.0)
    d = _st("D", 14.0)
    return [s1, s2, s3], (a, b, c, d)


# ─── 规格 §一.1：站点顺序 + 每站只停一次 ───────────────────────────


def _plan_stops(out):
    return [s for p in out.vehicle_plans for s in p.stops]


def test_regression1_each_skeleton_station_stopped_exactly_once():
    stations, pts = _leg_points()
    a, b, c, d = pts
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId=a.stationId),
              PlanOrder(orderId="K2", orderType=OrderType.PICKUP, stationId=c.stationId)]
    shipments = [PlanShipment(shipmentId="T1", pickupStationId=b.stationId,
                              deliveryStationId=d.stationId, quantity=1)]
    req = _request(stations + list(pts), vehicles, orders, shipments)
    out = solve(req, _matrix(stations + list(pts)))
    assert out.status == "feasible", out.reason_code
    passes = [s.stationId for s in _plan_stops(out) if s.action == StopAction.PASS]
    # 「每站必停拉客」+ 规格「每个站点只停靠一次」：PASS 序列必须是骨架本身，不重不漏
    assert passes == ["S1", "S2", "S3"], f"骨架停靠被重复/漏停：{passes}"


def test_station_pass_is_not_duplicated_across_visits():
    """同一骨架站被第二次经过时不得再产生 PASS 停靠（规格 §一.1「已停靠不再停」）。"""
    stations, pts = _leg_points()
    a, b, c, d = pts
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="D1", orderType=OrderType.DELIVERY, stationId=b.stationId),
              PlanOrder(orderId="D2", orderType=OrderType.DELIVERY, stationId=b.stationId)]
    req = _request(stations + list(pts), vehicles, orders, [])
    out = solve(req, _matrix(stations + list(pts)))
    if out.status != "feasible":
        pytest.skip(f"该fixture未给出方案：{out.reason_code}")
    passes = [s.stationId for s in _plan_stops(out) if s.action == StopAction.PASS]
    assert len(passes) == len(set(passes)), f"同站重复停靠：{passes}"


# ─── 规格 §二.2 / §四：地理候选区间 ≠ 执行归属区间 ──────────────────


def test_task_events_carry_exec_region_and_geo_candidates():
    """每个取货/送货动作都要能回答两件事：它在地理上属于哪些区间、它实际被哪段行程执行。"""
    stations, pts = _leg_points()
    a, b, c, d = pts
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId=a.stationId)]
    shipments = [PlanShipment(shipmentId="T1", pickupStationId=c.stationId,
                              deliveryStationId=d.stationId, quantity=1)]
    req = _request(stations + list(pts), vehicles, orders, shipments)
    out = solve(req, _matrix(stations + list(pts)))
    assert out.status == "feasible", out.reason_code
    cargo = [s for s in _plan_stops(out) if s.action in (StopAction.PICKUP, StopAction.DELIVER)]
    assert cargo, "fixture 必须产出货运停靠"
    for s in cargo:
        assert getattr(s, "execRegion", None) is not None, f"{s.orderId} 没有执行区间归属"
        assert getattr(s, "geoCandidates", None), f"{s.orderId} 没有地理候选区间"
        assert all(isinstance(x, int) for x in s.geoCandidates)


def test_exec_region_never_precedes_last_stopped_station():
    """执行区间的下界由「车辆此刻已停靠到第几个站」决定（规格 §四 allowed_exec）。"""
    stations, pts = _leg_points()
    a, b, c, d = pts
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId=a.stationId),
              PlanOrder(orderId="K2", orderType=OrderType.PICKUP, stationId=c.stationId)]
    req = _request(stations + list(pts), vehicles, orders, [])
    out = solve(req, _matrix(stations + list(pts)))
    assert out.status == "feasible", out.reason_code
    for plan in out.vehicle_plans:
        stopped = 0
        for s in plan.stops:
            if s.action == StopAction.PASS:
                stopped += 1
            elif s.action in (StopAction.PICKUP, StopAction.DELIVER):
                region = getattr(s, "execRegion", None)
                assert region is not None, f"{s.orderId} 缺 execRegion"
                assert region >= max(0, stopped - 1), (
                    f"{s.orderId} 在已停靠 {stopped} 站后被归到区间 {region}，"
                    "等于让车倒回已经走过的路段执行"
                )


# ─── 规格 §二.4 / §三：订单成对，但两条腿可以跨区间；并列时代价同则倾向同区 ──


def test_shipment_legs_may_be_assigned_to_different_regions():
    stations, pts = _leg_points()
    a, b, c, d = pts
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    shipments = [PlanShipment(shipmentId="T1", pickupStationId=b.stationId,
                              deliveryStationId=d.stationId, quantity=1)]
    req = _request(stations + list(pts), vehicles, [], shipments)
    out = solve(req, _matrix(stations + list(pts)))
    assert out.status == "feasible", out.reason_code
    legs = [s for s in _plan_stops(out) if s.orderId == "T1"]
    kinds = {s.action for s in legs}
    assert kinds == {StopAction.PICKUP, StopAction.DELIVER}, f"取送两条腿必须都在：{kinds}"
    pick, drop = [s for s in legs if s.action == StopAction.PICKUP][0], \
                 [s for s in legs if s.action == StopAction.DELIVER][0]
    assert pick.execRegion <= drop.execRegion, "取货的执行区间不能晚于送货"


# ─── 规格 §二.5：订单可舍弃（高但有限的惩罚），不是硬塞 ───────────────


def test_passenger_is_never_discarded_even_when_far():
    """业务底线：乘客不参与舍弃/降级。

    公交车每站必停拉客，到站是既定动作，不存在「为一个人绕路不值」的取舍；
    项目也没有线上预约乘车，所以也没有「改到别处上车」的降级路径。
    乘客唯一不能服务的原因是座位容量硬约束 —— 那时必须如实判无解，
    而不是交一个「少了两个人」的方案。
    """
    stations, pts = _leg_points()
    a, b, c, d = pts
    far_pax_board = _st("FARB", 200.0)
    far_pax_alight = _st("FALA", 205.0)
    all_st = stations + [a, b, c, d, far_pax_board, far_pax_alight]
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId=a.stationId),
              PlanOrder(orderId="PFAR", orderType=OrderType.PASSENGER,
                        boardingStationId=far_pax_board.stationId,
                        alightingStationId=far_pax_alight.stationId)]
    req = _request(all_st, vehicles, orders, [])
    out = solve(req, _matrix(all_st))
    if out.status != "feasible":
        # 如实判无解是可接受的（而不是硬塞或偷偷少一个人）
        assert "PFAR" not in (out.unassigned_order_ids or []), \
            "无解分支里把乘客列进 unassigned 等于宣称可以舍弃人"
        return
    ids = {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}
    assert "PFAR" in ids, "可行方案里乘客被丢掉了"
    assert "PFAR" not in (out.unassigned_order_ids or []), "乘客同时出现在方案和舍弃清单，口径矛盾"
    legs = [s.action for p in out.vehicle_plans for s in p.stops if s.orderId == "PFAR"]
    assert sorted(legs, key=str) == sorted([StopAction.BOARD, StopAction.ALIGHT], key=str), \
        f"乘客必须上下车成对：{legs}"


def test_over_priced_order_is_discarded_not_force_detoured():
    """把一个偏远到「绕行代价远超舍弃惩罚」的订单交给算法：
    正确行为是舍弃并说明原因；错误行为是像现在这样补插进去绕一大圈。"""
    stations, pts = _leg_points()
    a, b, c, d = pts
    far = _st("FAR", 1.0)          # 地理上贴着 R12 起点
    # 让 FAR 到最近骨架站的真实距离极大：把它横移出去（经度方向）
    far = Station(stationId="FAR", longitude=round(106.50 + 220 / 96.4, 6), latitude=DEPOT_LAT)
    all_st = stations + [a, b, c, d, far]
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId=a.stationId),
              PlanOrder(orderId="KFAR", orderType=OrderType.PICKUP, stationId=far.stationId)]
    req = _request(all_st, vehicles, orders, [])
    out = solve(req, _matrix(all_st))
    assert out.status == "feasible", out.reason_code
    discarded = list(getattr(out, "unassigned_order_ids", []) or [])
    assert "KFAR" in discarded, (
        f"超代价订单没有被舍弃（unassigned={discarded}）——"
        "说明算法仍在强行绕行"
    )
    assert not any(s.orderId == "KFAR" for p in out.vehicle_plans for s in p.stops), \
        "KFAR 既在方案里又被舍弃，口径矛盾"


def test_discard_reason_is_explained():
    stations, pts = _leg_points()
    a, b, c, d = pts
    far = Station(stationId="FAR", longitude=round(106.50 + 220 / 96.4, 6), latitude=DEPOT_LAT)
    all_st = stations + [a, b, c, d, far]
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=8,
                        skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="KFAR", orderType=OrderType.PICKUP, stationId=far.stationId)]
    req = _request(all_st, vehicles, orders, [])
    out = solve(req, _matrix(all_st))
    if out.status != "feasible":
        pytest.skip("本 fixture 在当前预算下未出方案，舍弃解释另行验证")
    if "KFAR" not in (out.unassigned_order_ids or []):
        pytest.skip("本 fixture 未触发舍弃，原因码另行验证")
    codes = " ".join(out.warnings or [])
    assert "DISCARD" in codes.upper() or "UNASSIGNED" in codes.upper(), codes


def test_outlet_has_no_over_threshold_onroute_stop():
    """超阈值货运停靠必须在出口被收敛：要么交接、要么摘单，不能继续上门绕。"""
    from benchmarks.lab import real_corpus as real
    inst = real.load("r_village-M-03x041")
    if inst is None:
        pytest.skip("真实语料未生成")
    req = inst.request.model_copy(update={"algorithmConfig": _cfg("HACO", 20261010, 25_000)})
    out = solve(req, inst.matrix)
    assert out.status == "feasible", out.reason_code
    over = [(s.orderId, s.detourDistance)
            for p in out.vehicle_plans for s in p.stops
            if s.action.value in ("PICKUP", "DELIVER") and (s.detourDistance or 0.0) > 3.0]
    assert not over, f"出口仍有超阈值在车绕行：{over}"
