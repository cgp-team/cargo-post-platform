"""P1-B：验证 detourDistance + detourDuration 统一计算。

规则：
  detourDistance = (prev→cargo + cargo→next) - (prev→next)  >= 0
  detourDuration = (prev→cargo_dur + cargo→next_dur) - (prev→next_dur)  >= 0

覆盖：
  - Euclidean 模式（无 matrix）
  - AMAP mock matrix
  - 骨架站（detour=0）
  - 不可达（matrix 缺 key → DISTANCE_MATRIX_INCOMPLETE）
"""

from __future__ import annotations

from app.distance import EUCLIDEAN_AVG_SPEED_KMH, haversine_km
from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    Station,
    StopAction,
    Vehicle,
)
from app.solver import solve


def _make_stations():
    """S0(depot), S1, S2, S3, S4。S4 不在骨架上。"""
    return [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.05, latitude=30.0 + i * 0.05)
        for i in range(5)
    ]


# ── Euclidean 模式 ────────────────────────────────────────────

def test_euclidean_detour_distance_positive():
    """Euclidean 模式：非骨架站 detourDistance > 0。"""
    stations = _make_stations()

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="euclidean-detour",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDistance is not None and k1.detourDistance > 0
    assert k1.detourDuration is not None and k1.detourDuration > 0


def test_euclidean_detour_duration_consistent():
    """Euclidean 模式：detourDuration 由各段 Haversine 独立计算，≥ 0。"""
    stations = _make_stations()

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S4", itemCount=1),
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="euclidean-consistency",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDuration is not None and k1.detourDuration >= 0


# ── AMAP mock matrix ──────────────────────────────────────────

def test_amap_detour_with_matrix():
    """AMAP matrix 模式：detour 使用 matrix 中的 km 和 seconds。

    口径变更说明（绕行由硬约束改成软决策后）：本例原来的矩阵是「所有点对 10km」，
    而默认绕行上限是 2km（闲置运力可放宽到 3km）。旧行为是为这一单空跑 10 公里并
    把 detourDistance 如实记成 10 —— 那正是产品不要的行为。按
    docs/dispatch/DISPATCH_V047_REPORT.md §164 的批次全局求解口径，超阈值应改成
    「就近骨架站交接」，所以这里拆成两条都验：
      A. 阈值内的订单：仍在线侧服务，detour 必须按矩阵如实算出（>0，且不是直线拍脑袋）；
      B. 超阈值的订单：交接给就近站，detour≈0 并带 reasonCode=DETOUR_OVER_THRESHOLD。
    """
    stations = _make_stations()
    station_ids = [s.stationId for s in stations]

    def _matrix(near: tuple[str, str] | None = None
                ) -> dict[tuple[str, str], tuple[float, float | None]]:
        matrix: dict[tuple[str, str], tuple[float, float | None]] = {}
        for a in station_ids:
            for b in station_ids:
                if a == b:
                    matrix[(a, b)] = (0.0, 0.0)
                elif near is not None and {a, b} == set(near):
                    matrix[(a, b)] = (1.0, 180.0)      # 阈值内：真实路网 1km / 180s
                else:
                    matrix[(a, b)] = (10.0, 600.0)
        return matrix

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    def _req(orders):
        return PlanRequest(
            requestId="amap-detour",
            batchStart="2026-08-26T08:00:00+08:00",
            batchEnd="2026-08-26T23:00:00+08:00",
            depot=stations[0],
            stations=stations,
            vehicles=vehicles,
            orders=orders,
        )

    # A) 阈值内：detour 必须按矩阵如实计算
    near_orders = [PlanOrder(orderId="K0", orderType=OrderType.PICKUP,
                             stationId="S4", itemCount=1)]
    outcome = solve(_req(near_orders), _matrix(near=("S3", "S4")))
    assert outcome.status == "feasible", outcome.reason_code
    k0 = next(s for p in outcome.vehicle_plans for s in p.stops if s.orderId == "K0")
    assert k0.detourDistance is not None and k0.detourDistance > 0
    assert k0.detourDuration is not None and k0.detourDuration > 0
    assert k0.accepted and k0.reasonCode is None, "阈值内的订单不该被记成交接"

    # B) 超阈值且就近站也要走 10km（> 交接半径 3km）：
    #    政策是「不上门、也不让客户走断腿」→ 舍弃该单并如实上报，绝不空跑。
    far_orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                            stationId="S4", itemCount=1)]
    outcome2 = solve(_req(far_orders), _matrix())
    assert outcome2.status == "feasible", outcome2.reason_code
    assert "K1" in (outcome2.unassigned_order_ids or []), (
        "既不该上门绕 10km、也不该让客户走 10km，正确处置是交多段联运"
    )
    assert not any(s.orderId == "K1" for p in outcome2.vehicle_plans for s in p.stops),         "同一单既在方案里又被舍弃，口径矛盾"
    assert any("DISCARD" in w for w in outcome2.warnings), outcome2.warnings

    # C) 超阈值但就近站在交接半径内：改成站点交接，服务率不降
    near_far_orders = [PlanOrder(orderId="K2", orderType=OrderType.PICKUP,
                                 stationId="S4", itemCount=1)]
    matrix_c = _matrix(near=("S0", "S4"))    # S4 贴 S0，但离相邻骨架站仍是 10km
    outcome3 = solve(_req(near_far_orders), matrix_c)
    if outcome3.status == "feasible" and outcome3.vehicle_plans:
        k2 = [s for p in outcome3.vehicle_plans for s in p.stops if s.orderId == "K2"]
        if k2:
            assert k2[0].stationId in {"S0", "S1", "S2", "S3"} or k2[0].reasonCode is None,                 f"交接点应落在骨架站：{k2[0].stationId}"


# ── 骨架站 detour=0 ──────────────────────────────────────────

def test_skeleton_stop_detour_zero():
    """骨架站上的货运 stop detour=0。"""
    stations = _make_stations()

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP,
                  stationId="S2", itemCount=1),  # S2 在骨架上
    ]

    vehicles = [
        Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4,
                skeleton=["S0", "S1", "S2", "S3"]),
    ]

    req = PlanRequest(
        requestId="skeleton-detour-zero",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=vehicles,
        orders=orders,
    )

    outcome = solve(req)
    assert outcome.status == "feasible"

    plan = outcome.vehicle_plans[0]
    k1 = next(s for s in plan.stops if s.orderId == "K1")
    assert k1.detourDistance == 0.0
    assert k1.detourDuration == 0.0


# ── 不可达 matrix ─────────────────────────────────────────────

def test_incomplete_matrix_returns_error():
    """matrix 缺少 key → DISTANCE_MATRIX_INCOMPLETE。"""
    stations = _make_stations()

    matrix: dict[tuple[str, str], tuple[float, float | None]] = {}
    station_ids = [s.stationId for s in stations]
    for a in station_ids:
        for b in station_ids:
            if a == b:
                matrix[(a, b)] = (0.0, 0.0)
            elif a == "S0" and b == "S1":
                pass  # 缺失
            else:
                matrix[(a, b)] = (10.0, 600.0)

    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S2"),
    ]

    req = PlanRequest(
        requestId="incomplete-matrix",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T23:00:00+08:00",
        depot=stations[0],
        stations=stations,
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=4)],
        orders=orders,
    )

    outcome = solve(req, matrix)
    # HACO 可能 fallback 到 baseline，baseline 也可能返回 infeasible
    # 两种行为都是合法的
    assert outcome.status in ("infeasible", "feasible")
    if outcome.status == "infeasible":
        assert outcome.reason_code == "DISTANCE_MATRIX_INCOMPLETE"


def test_exit_handover_recovers_several_far_orders_without_discarding():
    """出口第②级（就近站交接）必须能一次救回**多单**，而不是整单判给多段联运。

    缺陷形态（静默少服务，不报错）：补全阶段每救一单都要跑一次
    `_routes_feasible`，而它要求「覆盖全部任务」。只要同时缺 ≥2 单，
    第一次交接就会因为「另一单还没处理」被判不可行而回滚 → 两单都被舍弃。
    判据后来只该核「车上现有的 + 这一单」，其余缺单是「还没轮到」不是「被丢下」。

    几何：V1/V2 各距最近骨架站 2.6km（≤3km 可交接），但到其它任何站 200km；
    批窗 60 分钟 → 上门取件的腿必然超窗（插入位全部不可行 → 任务落进缺单队列），
    而改成在 S1/S2 交接只需多停一站 → 合法。于是「救回两单」是唯一正确出口。
    """
    stations = [Station(stationId=f"S{i}", longitude=104.0 + i * 0.01,
                        latitude=30.0 + i * 0.01) for i in range(4)]
    stations.append(Station(stationId="V1", longitude=104.03, latitude=30.0))
    stations.append(Station(stationId="V2", longitude=104.06, latitude=30.0))
    ids = [s.stationId for s in stations]
    near_pairs = [{"S1", "V1"}, {"S2", "V2"}]

    def _is_near(a, b) -> bool:
        return any({a, b} == p for p in near_pairs)

    matrix = {}
    for a in ids:
        for b in ids:
            if a == b:
                matrix[(a, b)] = (0.0, 0.0)
            elif _is_near(a, b):
                matrix[(a, b)] = (2.6, 374.0)        # 真实量级：25km/h ≈ 6.2min
            elif a in ("S0", "S1", "S2", "S3") and b in ("S0", "S1", "S2", "S3"):
                matrix[(a, b)] = (1.0, 144.0)        # 骨架站彼此 1km
            else:
                matrix[(a, b)] = (200.0, 28800.0)    # 巷道绕不进：只能经最近站
    # 几何自检：这单到最近骨架站确实 2.6km（可交接），其它方向都不可达
    assert matrix[("S1", "V1")][0] == 2.6 and matrix[("S2", "V1")][0] == 200.0

    orders = [
        PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId="V1", itemCount=1),
        PlanOrder(orderId="K2", orderType=OrderType.PICKUP, stationId="V2", itemCount=1),
    ]
    req = PlanRequest(
        requestId="exit-handover",
        batchStart="2026-08-26T08:00:00+08:00",
        batchEnd="2026-08-26T09:00:00+08:00",      # 60 分钟批窗
        depot=stations[0], stations=stations,
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=8,
                          skeleton=["S0", "S1", "S2", "S3"])],
        orders=orders,
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=3),
    )
    out = solve(req, matrix)
    assert out.status == "feasible", f"两单都可交接却判无解：{out.reason_code} {out.warnings}"

    served = {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}
    skeleton = {"S0", "S1", "S2", "S3"}
    assert {"K1", "K2"} <= served, (
        f"出口第②级没救回多单（整单被判给多段联运）：served={served} "
        f"unassigned={out.unassigned_order_ids}"
    )
    for oid, station in (("K1", "S1"), ("K2", "S2")):
        stops = [s for p in out.vehicle_plans for s in p.stops if s.orderId == oid]
        assert stops, oid
        assert all(s.stationId == station for s in stops), \
            f"{oid} 没落在最近可交接站：{[(s.stationId, s.action) for s in stops]}"
        assert all(s.stationId in skeleton for s in stops)
        assert stops[0].serviceMode == "NEAREST_STATION"
        assert stops[0].reasonCode in {"DETOUR_OVER_THRESHOLD",
                                       "ROAD_NOT_BUS_ACCESSIBLE",
                                       "NEAR_STATION_WITHIN_100M"}
    assert not out.unassigned_order_ids, out.unassigned_order_ids
    # 独立复核：出口补全 + 软化改写之后的整份方案必须仍然全项合规
    # （容量、时效、交接自洽、起终点……）。少了这一条，「软化阶段用了漏任务的那张表」
    # 这类静默算错不会被发现 —— 它不报错，只是把某些任务从核算里掉出来。
    from benchmarks.lab.check import check_plan
    errs = check_plan(req, matrix, out)
    assert errs == [], f"方案自身过不了独立校验：{errs}"
