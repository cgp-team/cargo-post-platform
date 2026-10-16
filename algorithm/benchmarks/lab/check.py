"""独立方案校验器（度量台的负控）。

solver 说自己「feasible」不算数：这里用 app/validators 的公开校验器 +
覆盖性 + 骨架顺序 + 绕行预算 + 里程复算 五道独立检查复核每一份返回方案。
任何一条不过 → 该方案计入 invalid_plan，绝不参与 gap 统计（否则算法可以靠
返回违规方案把「可行率」刷满）。
"""

from __future__ import annotations

from app.models import CargoSource, OrderType, StopAction
from app.validators import (
    service_duration,
    validate_skeleton,
    validate_time_window,
    validate_vehicle_plan,
)


def _assigned_ids(plans) -> set[str]:
    out = set()
    for p in plans:
        for s in p.stops:
            if s.orderId:
                out.add(s.orderId)
    return out


def check_plan(request, matrix, outcome) -> list[str]:
    """返回违规原因列表；空列表 = 方案合规。"""
    errs: list[str] = []
    plans = outcome.vehicle_plans or []
    if outcome.status != "feasible":
        return errs
    if not plans:
        return ["EMPTY_PLAN_MARKED_FEASIBLE"]

    orders_by_id = {o.orderId: o for o in request.orders}
    shipments_by_id = {s.shipmentId: s for s in request.shipments}
    vehicles_by_id = {v.vehicleId: v for v in request.vehicles}
    station_by_id = {s.stationId: s for s in request.stations}
    station_by_id[request.depot.stationId] = request.depot

    # 1) 每车综合校验（载荷 / 时序 / 订单先后）
    for plan in plans:
        veh = vehicles_by_id.get(plan.vehicleId)
        if veh is None:
            errs.append(f"UNKNOWN_VEHICLE:{plan.vehicleId}")
            continue
        ok, reason = validate_vehicle_plan(plan, veh, orders_by_id, shipments_by_id)
        if not ok:
            errs.append(f"PLAN_INVALID:{plan.vehicleId}:{reason}")

    # 2) 骨架顺序（每站必停、按序）
    for plan in plans:
        veh = vehicles_by_id.get(plan.vehicleId)
        if veh and veh.skeleton:
            ok, reason = validate_skeleton(plan, veh.skeleton)
            if not ok:
                errs.append(f"SKELETON:{plan.vehicleId}:{reason}")

    # 3) 覆盖性：所有订单/运单都要被服务，除非算法**如实声明**了舍弃
    #    （通用调度规格 §二.5：代价过高的订单可以舍弃，但要交给后端多段联运）。
    need = set(orders_by_id) | set(shipments_by_id)
    got = _assigned_ids(plans)
    declared = set(outcome.unassigned_order_ids or [])
    missing = sorted(need - got)
    undeclared = sorted(set(missing) - declared)
    if undeclared:
        errs.append("ORDER_UNSERVED:" + ",".join(undeclared[:6]))
    ghost = sorted(declared & got)
    if ghost:
        errs.append("DECLARED_BUT_SERVED:" + ",".join(ghost[:6]))
    unknown = sorted(got - need)
    if unknown:
        errs.append("PHANTOM_ORDER:" + ",".join(unknown[:6]))
    if declared - need:
        errs.append("DECLARED_UNKNOWN_ORDER:" + ",".join(sorted(declared - need)[:6]))

    # 4) 里程复算：segmentDistance 累加必须等于 totalDistance（否则 gap 失真）
    total = 0.0
    for plan in plans:
        seg = 0.0
        for i, stop in enumerate(plan.stops):
            if i == 0:
                continue
            prev = plan.stops[i - 1].stationId
            if stop.stationId not in station_by_id or prev not in station_by_id:
                errs.append(f"UNKNOWN_STATION:{stop.stationId}")
                continue
            if matrix is not None:
                km, _sec = matrix[(prev, stop.stationId)]
                if abs(km - stop.segmentDistance) > 0.01:
                    errs.append(
                        f"SEG_MISMATCH:{plan.vehicleId}:{prev}->{stop.stationId}"
                        f":{stop.segmentDistance:.3f}!={km:.3f}"
                    )
                seg += stop.segmentDistance
        if abs(seg - plan.totalDistance) > 0.02:
            errs.append(f"PLAN_TOTAL_MISMATCH:{plan.vehicleId}:{seg:.3f}!={plan.totalDistance:.3f}")
        total += seg
    if abs(total - (outcome.total_distance or 0.0)) > 0.05:
        errs.append(f"OUTCOME_TOTAL_MISMATCH:{total:.3f}!={outcome.total_distance:.3f}")

    # 5) 货运绕行/就近交接的自洽性
    #    口径来源：baseline 与 HACO 的既定约定 —— 货运经停一律 serviceMode=NEAREST_STATION，
    #    servicePoint = 本次服务真实发生的点位（即该停靠站本身）；只有真超阈值才 accepted=False。
    #    因此这里不发明新约定，只查两件事：
    #      a) 求解器**自己声称**做了「超阈值→改到就近骨架站」的（reasonCode=DETOUR_OVER_THRESHOLD），
    #         那个停靠必须真的落在骨架站上，否则就是假交接；
    #      b) 请求显式给了 maxDetourDistanceKm 时，accepted=True 的停靠绕行不得越界
    #         （请求没给阈值时不套用我们自己的数字——baseline 的语义是「没给就不约束」）。
    # 请求给了阈值就用它；没给则按算法自己的放宽上限（3km）恒查。
    # 早先只在「请求显式给阈值」时才检查，等于让软约束的执行情况完全不出现在指标里
    # —— 实测有实例留着 14.17km 的在车绕行而指标全绿。
    limit = float(getattr(request.algorithmConfig, "maxDetourDistanceKm", None) or 3.0)
    # 「已改成站点交接」的原因码族：超阈值绕行与公交根本进不去是两种成因，
    # 但出口形态一样（车停在骨架站上），所以复核判据也一样。
    handover_reasons = {"DETOUR_OVER_THRESHOLD", "ROAD_NOT_BUS_ACCESSIBLE",
                        "NEAR_STATION_WITHIN_100M"}
    for plan in plans:
        veh = vehicles_by_id.get(plan.vehicleId)
        skel = set(veh.skeleton) if (veh and veh.skeleton) else set()
        for stop in plan.stops:
            if stop.action not in (StopAction.PICKUP, StopAction.DELIVER):
                continue
            if stop.serviceMode == "NEAREST_STATION" and stop.servicePoint != stop.stationId:
                errs.append(f"SERVICE_POINT_MISMATCH:{plan.vehicleId}:{stop.orderId}")
            if stop.reasonCode in handover_reasons:
                if skel and stop.stationId not in skel:
                    errs.append(f"HANDOVER_NOT_AT_SKELETON:{plan.vehicleId}:{stop.orderId}"
                                f":{stop.stationId}")
            elif stop.accepted and limit is not None and skel \
                    and (stop.detourDistance or 0.0) > float(limit) + 1e-6 \
                    and stop.stationId not in skel:
                errs.append(
                    f"DETOUR_OVER_BUDGET:{plan.vehicleId}:{stop.orderId}"
                    f":{stop.detourDistance:.3f}>{limit}"
                )
            elif not stop.accepted and not stop.reasonCode:
                errs.append(f"UNEXPLAINED_REJECTION:{plan.vehicleId}:{stop.orderId}")
            # 业务侧判定「公交进不去 / 站点就在旁边」的点位，车绝不能停在那儿上门。
            # 这一条与绕行预算无关：600m 的偏移在预算内，但 10 米宽的巷子里 12 米长的
            # 公交车就是进不去 —— 停在巷口交接才是可执行的路单。
            od = orders_by_id.get(stop.orderId or "")
            if od is None:
                # 配对货运单（PlanShipment）同样带通行预检结论，而且它是生产主力形态（村→村）：
                # 只查 orders 的话，这条规则对绝大多数货运单等于没跑
                od = shipments_by_id.get(stop.orderId or "")
            ra = getattr(od, "roadAccess", None) if od is not None else None
            if ra and skel and stop.stationId not in skel:
                errs.append(f"ROAD_ACCESS_VISITED:{plan.vehicleId}:{stop.orderId}"
                            f":{stop.stationId}:{ra}")

    # 6) 时间窗：整批必须在 batchStart~batchEnd 内完成
    for plan in plans:
        ok, reason = validate_time_window(
            plan,
            int(request.batchStart.timestamp()),
            int(request.batchEnd.timestamp()),
        )
        if not ok:
            errs.append(f"TIME_WINDOW:{plan.vehicleId}:{reason}")

    # 6b) 逐单时效（PlanShipment.readyTime/pickupDeadline/deliveryDeadline，V047 §15）：
    #     批级窗口只能说明「这班车没超时」，说明不了「这一票按时」。
    #     时钟口径与 rule 6 同源（累计 segmentDuration + 每站服务时间），
    #     外加一条物理事实：货没到 readyTime 就取不到，车只能等到那一刻（等待计入时钟）。
    base_ts = request.batchStart.timestamp()
    for plan in plans:
        clock = base_ts
        picked: set[str] = set()
        for stop in plan.stops:
            if stop.segmentDuration:
                clock += float(stop.segmentDuration)
            sh = shipments_by_id.get(stop.orderId or "")
            if stop.action == StopAction.PICKUP and sh is not None and sh.readyTime is not None:
                clock = max(clock, sh.readyTime.timestamp())
            clock += service_duration(stop.action)
            if sh is None or stop.action not in (StopAction.PICKUP, StopAction.DELIVER):
                continue
            if stop.action == StopAction.PICKUP:
                picked.add(sh.shipmentId)
                if sh.pickupDeadline is not None and clock > sh.pickupDeadline.timestamp() + 1e-6:
                    errs.append(
                        f"ETA_MISSED_PICKUP:{plan.vehicleId}:{sh.shipmentId}"
                        f":{int(clock)}>{int(sh.pickupDeadline.timestamp())}"
                    )
            else:
                if sh.shipmentId not in picked:
                    errs.append(f"SHIPMENT_ORDER:{plan.vehicleId}:{sh.shipmentId}")
                if (sh.deliveryDeadline is not None
                        and clock > sh.deliveryDeadline.timestamp() + 1e-6):
                    errs.append(
                        f"ETA_MISSED_DELIVERY:{plan.vehicleId}:{sh.shipmentId}"
                        f":{int(clock)}>{int(sh.deliveryDeadline.timestamp())}"
                    )

    # 7) 乘客必须「先上后下」且同车
    for oid, o in orders_by_id.items():
        if o.orderType != OrderType.PASSENGER:
            continue
        if oid in declared:
            continue   # 已声明舍弃：整单都不在方案里才是正确形态（上面已校验）
        hits = [(p.vehicleId, i, s.action) for p in plans for i, s in enumerate(p.stops) if s.orderId == oid]
        board = [h for h in hits if h[2] == StopAction.BOARD]
        alight = [h for h in hits if h[2] == StopAction.ALIGHT]
        if not board or not alight:
            errs.append(f"PAX_SPLIT:{oid}")
        elif board[0][0] != alight[0][0] or board[0][1] >= alight[0][1]:
            errs.append(f"PAX_ORDER:{oid}")

    # 8) 多起点公交线：车辆必须从 startStationId 发车、收班于 endStationId
    #    （契约字段，baseline 已实现；漏检会让「按统一场站出收班」的方案蒙混过关）
    for plan in plans:
        veh = vehicles_by_id.get(plan.vehicleId)
        if veh is None or not plan.stops:
            continue
        first, last = plan.stops[0], plan.stops[-1]
        if veh.startStationId and first.stationId != veh.startStationId:
            errs.append(
                f"START_STATION:{plan.vehicleId}:{first.stationId}!={veh.startStationId}"
            )
        if veh.endStationId and last.stationId != veh.endStationId:
            errs.append(f"END_STATION:{plan.vehicleId}:{last.stationId}!={veh.endStationId}")

    # 9) 载重 / 体积剖面（独立复算，不看 solver 自己的判断）
    #    出程派送件发车即在舱内 → 起始载荷 = 该车所有非 PRELOADED 派送件之和；
    #    揽收/配对运单在揽收时 +，派送时 -。任一点超每车上限即违规。
    for plan in plans:
        veh = vehicles_by_id.get(plan.vehicleId)
        if veh is None:
            continue
        w_cap = getattr(veh, "cargoWeightCapacityKg", None)
        v_cap = getattr(veh, "cargoVolumeCapacityM3", None)
        if w_cap is None and v_cap is None:
            continue

        def _legw(oid: str | None) -> tuple[float, float]:
            o = orders_by_id.get(oid or "")
            if o is not None:
                return (float(o.weightKg or 0.0), float(o.volumeM3 or 0.0))
            sh = shipments_by_id.get(oid or "")
            if sh is not None:
                return (float(sh.weightKg or 0.0), float(sh.volumeM3 or 0.0))
            return (0.0, 0.0)

        preload_w = 0.0
        preload_v = 0.0
        for stop in plan.stops:
            o = orders_by_id.get(stop.orderId or "")
            if (o is not None and o.orderType == OrderType.DELIVERY
                    and o.cargoSource != CargoSource.PRELOADED):
                preload_w += float(o.weightKg or 0.0)
                preload_v += float(o.volumeM3 or 0.0)
        w, v = preload_w, preload_v
        peak_w, peak_v = w, v
        for stop in plan.stops:
            if stop.action == StopAction.PICKUP:
                dw, dv = _legw(stop.orderId)
                w += dw
                v += dv
            elif stop.action == StopAction.DELIVER:
                o = orders_by_id.get(stop.orderId or "")
                if o is not None and o.orderType == OrderType.DELIVERY:
                    continue   # 派送件的重量已在起始载荷里，卸货不重复扣
                dw, dv = _legw(stop.orderId)
                w -= dw
                v -= dv
            peak_w = max(peak_w, w)
            peak_v = max(peak_v, v)
        if w_cap is not None and peak_w > float(w_cap) + 1e-6:
            errs.append(f"WEIGHT_OVERLOAD:{plan.vehicleId}:{peak_w:.1f}>{w_cap}")
        if v_cap is not None and peak_v > float(v_cap) + 1e-6:
            errs.append(f"VOLUME_OVERLOAD:{plan.vehicleId}:{peak_v:.2f}>{v_cap}")

    # 10) 配对运单不得两腿塌到同一站（否则货从未被运输却报称已服务）
    for plan in plans:
        legs: dict[str, list[tuple[str, str]]] = {}
        for stop in plan.stops:
            if stop.action in (StopAction.PICKUP, StopAction.DELIVER) and stop.orderId in shipments_by_id:
                legs.setdefault(stop.orderId, []).append((stop.action.value, stop.stationId))
        for oid, seq in legs.items():
            kinds = {a for a, _ in seq}
            if kinds == {"PICKUP", "DELIVER"} and len({st for _, st in seq}) == 1:
                errs.append(f"PAIR_COLLAPSED_SAME_STATION:{plan.vehicleId}:{oid}:{seq[0][1]}")

    return sorted(set(errs))
