"""载重 / 体积运力：契约字段必须真的约束方案（业务风险：超载）。

现状（写这份用例时已核实）：
- `FeasibilityEngine` 的载重/体积校验脚手架是完整的
  （feasibility_engine.py:443-516 累计 weight_load/volume_load 并比对每车上限，
   返回 CARGO_WEIGHT/VOLUME_CAPACITY_EXCEEDED）；
- 但两头没接：`_encode_tasks` 从不把 PlanOrder/PlanShipment 的 weightKg/volumeM3
  写进 TaskBlock，v14_solver 构造引擎时也从不传 cargoWeightCapacityKg 的每车字典
  → 那段校验永远走不到，等于**超载不报错**。

数值口径：字段本身由契约定义（Vehicle.cargoWeightCapacityKg / cargoVolumeCapacityM3、
PlanOrder.weightKg / volumeM3）；本用例取「公交车货舱 30kg / 0.75m³，单件重货 50kg /
0.5m³」这种现实量级，验证的是机制而不是某个特定阈值。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.distance import haversine_km
from app.models import (
    AlgorithmConfig,
    CargoSource,
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

from benchmarks.lab.check import check_plan

LAT0, LON0 = 29.55, 106.50
KM_PER_DEG_LAT = 110.6


def _st(sid: str, km: float, lat_off: float = 0.0) -> Station:
    return Station(stationId=sid, longitude=LON0 + lat_off,
                   latitude=round(LAT0 + km / KM_PER_DEG_LAT, 6))


def _matrix(stations):
    m = {}
    for a in stations:
        for b in stations:
            if a.stationId == b.stationId:
                m[(a.stationId, b.stationId)] = (0.0, 0.0)
            else:
                km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
                m[(a.stationId, b.stationId)] = (round(km, 4), round(km / 25 * 3600, 1))
    return m


def _request(stations, vehicles, orders, shipments=()):
    start = datetime(2026, 10, 8, 8, 0, tzinfo=timezone.utc)
    return PlanRequest(
        requestId="cap-case", batchStart=start, batchEnd=start + timedelta(hours=6),
        depot=stations[0], stations=stations, vehicles=vehicles,
        orders=orders, shipments=list(shipments),
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=11),
    )


def _line(n=4):
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0)]
    stops = [_st(f"V{i}", 1.0 + 2.0 * i, lat_off=0.01 * i) for i in range(1, n + 1)]
    return stations, stops


def _served_ids(out):
    return {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}


# ─── 独立复核：方案本身的载重剖面 ────────────────────────────────

def _weight_profile_violation(request, out) -> list[str]:
    """沿每条路线累计重量/体积，返回越界描述（与 solver 用什么引擎无关的独立算法）。"""
    by_id = {o.orderId: o for o in request.orders}
    sh_by_id = {s.shipmentId: s for s in request.shipments}
    veh_by_id = {v.vehicleId: v for v in request.vehicles}
    bad = []
    for plan in out.vehicle_plans:
        veh = veh_by_id.get(plan.vehicleId)
        if veh is None:
            continue
        # 派送件发车即在舱内：起始载荷 = 本车所有非 PRELOADED 派送件
        w = v = 0.0
        for stop in plan.stops:
            o = by_id.get(stop.orderId or "")
            if o is not None and o.orderType == OrderType.DELIVERY                     and o.cargoSource != CargoSource.PRELOADED:
                w += float(o.weightKg or 0.0)
                v += float(o.volumeM3 or 0.0)
        start_w, start_v = w, v
        for stop in plan.stops:
            if stop.action == StopAction.PICKUP:
                o = by_id.get(stop.orderId)
                s = sh_by_id.get(stop.orderId)
                w += float((o.weightKg if o else s.weightKg) or 0.0)
                v += float((o.volumeM3 if o else s.volumeM3) or 0.0)
            elif stop.action == StopAction.DELIVER:
                o = by_id.get(stop.orderId)
                s = sh_by_id.get(stop.orderId)
                if o is not None and o.orderType == OrderType.DELIVERY:
                    w -= float(o.weightKg or 0.0)      # 已在起始载荷里，卸货扣回
                    v -= float(o.volumeM3 or 0.0)
                    continue
                w -= float((o.weightKg if o else s.weightKg) or 0.0)
                v -= float((o.volumeM3 if o else s.volumeM3) or 0.0)
            if veh.cargoWeightCapacityKg is not None and w > veh.cargoWeightCapacityKg + 1e-6:
                bad.append(f"{plan.vehicleId} 载重 {w:.1f}kg > 上限 {veh.cargoWeightCapacityKg}kg")
            if veh.cargoVolumeCapacityM3 is not None and v > veh.cargoVolumeCapacityM3 + 1e-6:
                bad.append(f"{plan.vehicleId} 体积 {v:.2f}m³ > 上限 {veh.cargoVolumeCapacityM3}m³")
    return bad


def test_plan_never_exceeds_weight_capacity():
    """任何返回的方案都不得超每车重量上限 —— 现在这条是红的（超载被静默装载）。"""
    stations, stops = _line(3)
    all_st = stations + stops
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                        cargoWeightCapacityKg=30.0, skeleton=["S1", "S2", "S3"])]
    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId=stops[0].stationId,
                  itemCount=1, weightKg=12.0),
        PlanOrder(orderId="K2", orderType=OrderType.PICKUP, stationId=stops[1].stationId,
                  itemCount=1, weightKg=12.0),
        # 单件就超过整舱上限：绝对不可能合法装载
        PlanOrder(orderId="KBIG", orderType=OrderType.PICKUP, stationId=stops[2].stationId,
                  itemCount=1, weightKg=50.0),
    ]
    req = _request(all_st, vehicles, orders)
    out = solve(req, _matrix(all_st))
    assert out.reason_code != "INTERNAL_ERROR", (
        f"solver 异常被当成无解蒙混过关：{out.warnings}"
    )
    if out.status != "feasible":
        # 如实判无解是正确行为之一，但原因必须是业务约束，不是内部错误
        assert out.reason_code in {"OVER_CAPACITY", "TIMING_CONFLICT",
                                   "TIME_WINDOW_EXCEEDED", "INCOMPLETE_SOLUTION",
                                   "NO_FEASIBLE_INITIAL_SOLUTION", "PRELOAD_INSUFFICIENT"}, out.reason_code
        return
    assert _weight_profile_violation(req, out) == [], (
        f"方案超载却仍被判 feasible：{_weight_profile_violation(req, out)}"
    )
    # 单件超上限的订单必须不在方案里（要么舍弃交多段联运，要么整批判无解）
    assert "KBIG" not in _served_ids(out), "超过整舱上限的单件被装上了车"


def test_plan_never_exceeds_volume_capacity():
    stations, stops = _line(3)
    all_st = stations + stops
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                        cargoVolumeCapacityM3=0.75, skeleton=["S1", "S2", "S3"])]
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY, stationId=stops[0].stationId,
                  itemCount=1, volumeM3=0.4),
        PlanOrder(orderId="D2", orderType=OrderType.DELIVERY, stationId=stops[1].stationId,
                  itemCount=1, volumeM3=0.4),
    ]
    req = _request(all_st, vehicles, orders)
    out = solve(req, _matrix(all_st))
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status != "feasible":
        return
    assert _weight_profile_violation(req, out) == [], "体积超限仍被判可行"


def test_shipment_weight_counts_on_board_between_legs():
    """运单揽收→派送之间必须占舱；同时占舱超上限时必须拒掉一票。

    可证明的重叠：两票都从骨架站揽收、都在最后一站 S3 交付。公交车必须按
    S1→S2→S3 顺序停靠，所以 S2 揽收之后、S3 交付之前两票必然同舱
    → 28+10=38kg > 30kg，两票不能都接（可以只接一票，或整批判无解）。

    注：把取送点都放在离线点上是**不能**构成强制重叠的 —— 车可以先跑完一票
    再跑另一票（实测算法就是这么序列化的），那种断言是我一开始的误设。
    """
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0)]
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                        cargoWeightCapacityKg=30.0, skeleton=["S1", "S2", "S3"])]
    shipments = [
        PlanShipment(shipmentId="T1", pickupStationId="S1",
                     deliveryStationId="S3", quantity=1, weightKg=28.0),
        PlanShipment(shipmentId="T2", pickupStationId="S2",
                     deliveryStationId="S3", quantity=1, weightKg=10.0),
    ]
    req = _request(stations, vehicles, [], shipments)
    out = solve(req, _matrix(stations))
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status != "feasible":
        return
    assert _weight_profile_violation(req, out) == [], \
        f"同舱 38kg 超 30kg 仍被判可行：{_weight_profile_violation(req, out)}"
    # 不另加「必须拒掉一票」的断言：骨架顺序只约束 PASS 停靠，货腿可以挂在重访上，
    # 实测算法靠「先送完 T1、再回 S2 揽 T2」合法地避免了同舱重叠。
    # 唯一真正的硬不变量是载重剖面，已由上面的 _weight_profile_violation 独立复核。


def test_outbound_delivery_weight_is_forced_overlap():
    """派送件发车即在舱内：多件重量必须求和比上限，这是无法靠排序回避的重叠。"""
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0)]
    stops = [_st("V1", 1.0, 0.01), _st("V2", 3.0, 0.02)]
    all_st = stations + stops
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                        cargoWeightCapacityKg=30.0, skeleton=["S1", "S2", "S3"])]
    orders = [
        PlanOrder(orderId="D1", orderType=OrderType.DELIVERY, stationId="V1",
                  itemCount=1, weightKg=20.0),
        PlanOrder(orderId="D2", orderType=OrderType.DELIVERY, stationId="V2",
                  itemCount=1, weightKg=20.0),
    ]
    req = _request(all_st, vehicles, orders)
    out = solve(req, _matrix(all_st))
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status != "feasible":
        return
    assert _weight_profile_violation(req, out) == [], \
        f"出程同舱 40kg 超 30kg 仍可行：{_weight_profile_violation(req, out)}"
    served = {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}
    assert not ({"D1", "D2"} <= served), "两件 20kg 派送件同时出车 = 40kg > 30kg"


def test_lab_checker_detects_overload():
    """负控：手工造一个超载方案，独立校验器必须抓到（否则上面的绿灯是假的）。"""
    stations, stops = _line(2)
    all_st = stations + stops
    veh = Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                  cargoWeightCapacityKg=30.0, skeleton=["S1", "S2", "S3"])
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                        stationId=stops[0].stationId, itemCount=1, weightKg=40.0)]
    req = _request(all_st, [veh], orders)
    from app.models import RouteStop, VehiclePlan
    m = _matrix(all_st)
    bad_plan = VehiclePlan(vehicleId=1000, totalDistance=0.0, stops=[
        RouteStop(stationId="S1", action=StopAction.DEPART, segmentDistance=0.0, segmentDuration=0.0),
        RouteStop(stationId=stops[0].stationId, orderId="K1", action=StopAction.PICKUP,
                  segmentDistance=m[("S1", stops[0].stationId)][0],
                  segmentDuration=m[("S1", stops[0].stationId)][1]),
        RouteStop(stationId="S2", action=StopAction.RETURN,
                  segmentDistance=m[(stops[0].stationId, "S2")][0],
                  segmentDuration=m[(stops[0].stationId, "S2")][1]),
    ])
    bad_plan.totalDistance = round(sum(s.segmentDistance for s in bad_plan.stops), 3)

    class _Fake:
        status = "feasible"
        vehicle_plans = [bad_plan]
        total_distance = bad_plan.totalDistance
        unassigned_order_ids = []
        warnings = []

    # 独立剖面复核要先能看见超载
    assert _weight_profile_violation(req, _Fake()), "剖面复核本身失效"
    # 校验器走的是 solver 侧口径，超载必须由新增的独立检查抓到
    errs = check_plan(req, m, _Fake())
    assert any("WEIGHT" in e or "CAPACITY" in e for e in errs), errs


def test_freight_overflow_never_cancels_the_passenger_service():
    """业务底线用例：货装不下只该舍货，不该把整班公交（连同车上乘客）判无解。

    以前 `_precheck` 见 Σ货运需求 > Σ货仓 就直接 OVER_CAPACITY 回绝，
    同批乘客因此没有方案 —— 而乘客是每站必停要拉的人，不可舍弃。
    这一条把两件事分开钉住：乘客全数在服务里，超出的货运单如实进 unassigned。
    """
    stations, stops = _line(2)
    all_st = stations + stops
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=2,
                        skeleton=["S1", "S2", "S3"])]
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3"),
        PlanOrder(orderId="P2", orderType=OrderType.PASSENGER,
                  boardingStationId="S2", alightingStationId="S3"),
        # 3 件揽收 > 2 件舱位：可证明带不动
        PlanOrder(orderId="K3", orderType=OrderType.PICKUP,
                  stationId=stops[0].stationId, itemCount=3),
    ]
    req = _request(all_st, vehicles, orders)
    out = solve(req, _matrix(all_st))
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    assert out.status == "feasible", \
        f"一票带不动的货运让整批判无解，乘客因此没车可坐：{out.reason_code}"
    served = _served_ids(out)
    assert {"P1", "P2"} <= served, f"乘客被连带牺牲：served={served}"
    assert "K3" not in served
    assert "K3" in set(out.unassigned_order_ids or []), "超舱揽收未如实申报"
    assert _weight_profile_violation(req, out) == []


def test_within_capacity_still_served():
    """正向对照：容量内的订单不该被误拒（防止实现成「一律拒绝」来骗过前几条用例）。"""
    stations, stops = _line(2)
    all_st = stations + stops
    vehicles = [Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                        cargoWeightCapacityKg=30.0, skeleton=["S1", "S2", "S3"])]
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                        stationId=stops[0].stationId, itemCount=1, weightKg=8.0)]
    req = _request(all_st, vehicles, orders)
    out = solve(req, _matrix(all_st))
    assert out.status == "feasible", out.reason_code
    assert "K1" in _served_ids(out), "容量内的订单被误拒"
