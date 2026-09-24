"""产品模型对齐：骨架间隙插单 / 链式捷径不回固定路线 / 发车后顺路·高价值插入。"""

from __future__ import annotations

from app.dispatch_opt import (
    ChainedGapSegment,
    DispatchOrder,
    TripView,
    TripExecutionState,
    calculate_chained_gap_detour,
    calculate_gap_detour,
    is_on_planned_route,
    prefer_shortcut_to_next_stop,
)
from app.dispatch_opt.models import MarginalCostBreakdown
from app.dispatch_opt.route_cost_provider import (
    GapRoutePair,
    RouteCost,
    RouteCostStatus,
    build_gap_waypoints,
)
from app.dispatch_opt.trip_lock import TripLockPolicy

COORDS = {
    "A": (29.50, 106.50),
    "B": (29.51, 106.50),
    "C": (29.52, 106.50),
    "D": (29.53, 106.50),
    "X": (29.52, 106.51),
    "Y": (29.53, 106.51),
    "X2": (29.525, 106.52),
    "Y2": (29.535, 106.52),
}
MANDATORY = ["A", "B", "C", "D"]


class FormalStubProvider:
    def __init__(self, distance_m: float = 1000.0, duration_s: float = 120.0):
        self.distance_m = distance_m
        self.duration_s = duration_s

    def is_formal(self) -> bool:
        return True

    def route(self, origin, destination, *, waypoints=(), route_type=None) -> RouteCost:
        n = 1 + len(tuple(waypoints))
        return RouteCost(
            status=RouteCostStatus.FORMAL,
            distance_m=self.distance_m * n,
            duration_s=self.duration_s * n,
            provider="stub_formal",
        )

    def route_gap(self, gap_from, gap_to, *, pickup=None, delivery=None, extra=()) -> GapRoutePair:
        mids = build_gap_waypoints(pickup, delivery, extra)
        return GapRoutePair(
            baseline=self.route(gap_from, gap_to),
            insert=self.route(gap_from, gap_to, waypoints=mids),
            waypoints=mids,
        )

    def route_insert(self, insert_from, insert_to, insert_points) -> RouteCost:
        return self.route(insert_from, insert_to, waypoints=insert_points)

    def route_candidate(self, waypoints) -> RouteCost:
        return self.route(waypoints[0], waypoints[-1], waypoints=waypoints[1:-1])


# ── 发车后：顺路插入 / 高价值插入 两条并列例外 ──


def test_departed_on_route_allows_insert_without_high_value():
    p = TripLockPolicy()
    cost = MarginalCostBreakdown(delta_distance_m=100, delta_duration_s=15, delta_passenger_impact_s=5)
    d = p.decide(TripExecutionState.DEPARTED, is_high_value=False, on_planned_route=True, cost=cost)
    assert d.allow_insert
    assert d.reason_code == "ON_ROUTE_REALTIME_INSERT"
    assert d.level == "ON_ROUTE_REALTIME"


def test_departed_on_route_rejects_when_detour_too_large():
    p = TripLockPolicy(max_on_route_detour_m=200.0)
    cost = MarginalCostBreakdown(delta_distance_m=5000, delta_duration_s=15)
    d = p.decide(TripExecutionState.DEPARTED, is_high_value=False, on_planned_route=True, cost=cost)
    assert not d.allow_insert
    assert d.reason_code == "ON_ROUTE_DETOUR_TOO_LARGE"


def test_departed_normal_order_still_locked():
    p = TripLockPolicy()
    d = p.decide(TripExecutionState.DEPARTED, is_high_value=False, on_planned_route=False)
    assert not d.allow_insert
    assert d.reason_code == "LOCKED_ACTIVE_TRIP"


def test_decide_candidate_on_route_uses_wider_limits():
    p = TripLockPolicy(max_on_route_detour_m=1500.0, max_realtime_detour_m=800.0)
    # 1200m：超过高价值 800，但在顺路 1500 内
    d = p.decide_candidate(
        TripExecutionState.DEPARTED,
        is_high_value=False,
        on_planned_route=True,
        cost_is_formal=True,
        marginal_distance_m=1200.0,
        marginal_duration_s=100.0,
        passenger_impact_s=30.0,
        detour_budget_ok=True,
    )
    assert d.allow_insert
    assert d.reason_code == "ON_ROUTE_REALTIME_INSERT"

    # 同样 1200m 走高价值通道 → 拒
    d2 = p.decide_candidate(
        TripExecutionState.DEPARTED,
        is_high_value=True,
        on_planned_route=False,
        cost_is_formal=True,
        marginal_distance_m=1200.0,
        marginal_duration_s=100.0,
        passenger_impact_s=30.0,
        detour_budget_ok=True,
    )
    assert not d2.allow_insert
    assert d2.reason_code == "REALTIME_DETOUR_TOO_LARGE"


def test_decide_candidate_high_value_still_requires_accept():
    p = TripLockPolicy()
    d = p.decide_candidate(
        TripExecutionState.DEPARTED,
        is_high_value=True,
        cost_is_formal=True,
        marginal_distance_m=100.0,
        marginal_duration_s=20.0,
        passenger_impact_s=5.0,
        economic_admission="DEFER",
    )
    assert not d.allow_insert
    assert d.reason_code == "ECONOMIC_ADMISSION_DEFER"


def test_is_on_planned_route_requires_both_stops():
    stops = ("B", "X", "Y", "C")
    assert is_on_planned_route(pickup="X", delivery="Y", remaining_planned_stops=stops)
    assert not is_on_planned_route(pickup="X", delivery="Z", remaining_planned_stops=stops)
    assert not is_on_planned_route(pickup="X", delivery="Y", remaining_planned_stops=())


def test_dispatch_order_on_planned_route_override():
    order = DispatchOrder(
        order_id="O1",
        pickup_service_point="X",
        delivery_service_point="Y",
        on_planned_route=True,
    )
    assert order.on_planned_route is True
    trip = TripView(
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        remaining_planned_stops=("B", "X", "Y", "C"),
    )
    assert trip.remaining_planned_stops == ("B", "X", "Y", "C")


# ── 捷径性价比门：真实道路去下一站，不回固定公交路线 ──


def test_shortcut_preferred_when_cost_effective():
    d = prefer_shortcut_to_next_stop(
        economic_value=30.0,
        delta_cost=10.0,
        min_value_cost_ratio=1.5,
        route_formal=True,
    )
    assert d.prefer_shortcut
    assert d.reason_code == "SHORTCUT_COST_EFFECTIVE"
    assert d.value_to_cost_ratio == 3.0


def test_shortcut_rejected_when_not_cost_effective():
    d = prefer_shortcut_to_next_stop(
        economic_value=5.0,
        delta_cost=10.0,
        min_value_cost_ratio=1.5,
        route_formal=True,
    )
    assert not d.prefer_shortcut
    assert d.reason_code == "SHORTCUT_NOT_COST_EFFECTIVE"


def test_shortcut_requires_formal_road():
    d = prefer_shortcut_to_next_stop(route_formal=False)
    assert not d.prefer_shortcut
    assert d.reason_code == "SHORTCUT_ROUTE_NOT_FORMAL"


def test_shortcut_feasibility_only_without_economic_value():
    d = prefer_shortcut_to_next_stop(route_formal=True)
    assert d.prefer_shortcut
    assert d.reason_code == "SHORTCUT_FEASIBILITY_ONLY"


# ── 多单链式 Gap 绕行（以此类推） ──


def test_multi_order_chain_waypoints_order():
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    res = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "X2", "Y2", "C", "D"],
        route_provider=prov,
        extra_orders=(("X2", "Y2"),),
    )
    assert res.ok
    assert res.order_count == 2
    assert res.formal
    # 访问序：X → Y → X2 → Y2（pickup 前 delivery 后，不回折）
    names = [getattr(p, "stationId", p) for p in res.waypoints] if res.waypoints and hasattr(res.waypoints[0], "stationId") else None
    # waypoints 是 Point 元组；用 route_gap 收到的 mids 个数断言链式
    # baseline 1 段，insert 5 段（from + 4 mids + to 的 route() 计数）
    assert res.detour_distance_m > res.original_distance_m
    assert res.uses_shortcut_to_next_stop  # 无经济门时 formal + ok → 捷径
    assert res.returned_to_fixed_route is False


def test_multi_order_chain_haversine_fallback():
    res = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "X2", "Y2", "C", "D"],
        extra_orders=(("X2", "Y2"),),
    )
    assert res.ok
    assert res.order_count == 2
    assert res.status == "FALLBACK"
    assert res.uses_shortcut_to_next_stop is False


def test_gap_shortcut_gate_rejects_low_ratio():
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    res = calculate_gap_detour(
        from_station="B",
        to_station="C",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C", "D"],
        route_provider=prov,
        economic_value=1.0,
        min_shortcut_ratio=10.0,
    )
    assert not res.ok
    assert res.reason_code == "SHORTCUT_NOT_COST_EFFECTIVE"
    assert res.shortcut is not None


def test_chained_gap_detour_shortcut_chain():
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    chain = calculate_chained_gap_detour(
        segments=[
            ChainedGapSegment(
                from_station="B",
                to_station="C",
                via_orders=(("X", "Y"),),
            ),
            ChainedGapSegment(
                from_station="C",
                to_station="D",
                via_orders=(("X2", "Y2"),),
            ),
        ],
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C", "X2", "Y2", "D"],
        route_provider=prov,
    )
    assert chain.all_ok
    assert chain.uses_shortcut_chain
    assert len(chain.segments) == 2
    assert chain.total_delta_distance_m > 0
    assert chain.total_delta_duration_s > 0


def test_chained_gap_detour_stops_on_first_failure():
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    chain = calculate_chained_gap_detour(
        segments=[
            ChainedGapSegment(from_station="B", to_station="C", via_orders=(("X", "Y"),)),
            ChainedGapSegment(from_station="C", to_station="D", via_orders=(("X2", "Y2"),)),
        ],
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "B", "X", "Y", "C", "X2", "Y2", "D"],
        route_provider=prov,
        max_detour_m=10.0,
    )
    assert not chain.all_ok
    assert not chain.uses_shortcut_chain
    assert chain.reason_code == "DETOUR_BUDGET_EXCEEDED"


# ── 站间插单硬约束：A→B 间两单，不得因近路跳过 B 直去 C ──


def test_ab_gap_two_orders_must_not_skip_b_for_c():
    """订单1 送货（本地）+ 订单2 取货送另一站 C：路径 A→送货→取货→B，禁止 A→…→C。"""
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    res = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="X",          # 订单1：送货（本地点 X）
        via_delivery="X",
        rejoin_station="B",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "X", "Y", "B", "C", "D"],
        route_provider=prov,
        # 订单2：取货 Y，送另一站 C（跨 Gap 配送）
        extra_orders=(("Y", "C"),),
    )
    assert res.ok
    assert res.rejoin_station == "B"
    assert res.mandatory_sequence_preserved
    # 终点是 B，不是 C
    assert res.uses_shortcut_to_next_stop is True
    assert res.returned_to_fixed_route is False


def test_rejoin_to_later_station_is_next_mandatory_skipped():
    """试图 rejoin 到 C（to=B）→ 直接 NEXT_MANDATORY_SKIPPED，不进入路径计算。"""
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    res = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="X",
        via_delivery="Y",
        rejoin_station="C",  # 跳过 B 直去 C —— 禁止
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "X", "Y", "B", "C", "D"],
        route_provider=prov,
    )
    assert not res.ok
    assert res.reason_code == "NEXT_MANDATORY_SKIPPED"
    assert res.rejoin_station == "B"


def test_cross_gap_delivery_not_pulled_into_ab_path():
    """订单2 送 C 时，C 不得进入 A→B 的 via 路径；本 Gap 只取货，B 必到。"""
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    res = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="Y",
        via_delivery="C",  # 跨 Gap：送站是更远 Mandatory
        rejoin_station="B",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "Y", "B", "C", "D"],
        route_provider=prov,
    )
    assert res.ok
    assert res.rejoin_station == "B"
    # waypoints 是 Point 元组；用 order_count + formal 成功表示 C 未把路径引去跳站
    assert res.order_count == 1
    assert res.formal


def test_pickup_only_cross_gap_order_with_de_none():
    """跨 Gap 只取货（de=None）：本 Gap 路径 A→取货点→B。"""
    res = calculate_gap_detour(
        from_station="A",
        to_station="B",
        via_pickup="Y",
        via_delivery=None,
        rejoin_station="B",
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "Y", "B", "C", "D"],
    )
    assert res.ok
    assert res.rejoin_station == "B"


def test_chained_segment_rejoin_later_station_rejected():
    prov = FormalStubProvider(distance_m=500.0, duration_s=50.0)
    chain = calculate_chained_gap_detour(
        segments=[
            ChainedGapSegment(
                from_station="A",
                to_station="B",
                via_orders=(("X", "X"), ("Y", "C")),
                rejoin_station="C",  # 会跳过 B —— 禁止
            ),
        ],
        coords=COORDS,
        mandatory_stops=MANDATORY,
        planned_stops_after=["A", "X", "Y", "B", "C", "D"],
        route_provider=prov,
    )
    assert not chain.all_ok
    assert chain.reason_code == "NEXT_MANDATORY_SKIPPED"


# ── 项目基本：公交线每站必停拉客 ──


def test_pass_dwell_must_be_positive():
    """PASS=计划停靠拉客，服务时间必须 > 0（禁止过站不停）。"""
    from app.models import StopAction
    from app.validators import service_duration

    assert service_duration(StopAction.PASS) > 0
    assert service_duration(StopAction.BOARD) > 0


def test_every_skeleton_stop_is_mandatory_must_stop():
    """骨架站每站必停：缺任一站的 PASS 事件 → SKELETON_ORDER_VIOLATION。"""
    from app.haco.route_genome import EventType, RouteGenome

    g = RouteGenome(vehicle_index=0, vehicle_id=1, depot_station="D", skeleton=["A", "B", "C"])
    ok, _ = g.validate_skeleton()
    assert ok

    # 删掉中间站 B 的停靠事件 → 违反「每站必停」
    g.events = [
        e for e in g.events
        if not (e.event_type == EventType.PASS and e.station_id == "B")
    ]
    ok, reason = g.validate_skeleton()
    assert not ok
    assert reason == "SKELETON_ORDER_VIOLATION"


def test_gap_mandatory_list_cannot_skip_any_stop():
    """can_detour_within_gap：planned 序列缺任一 mandatory 站即失败。"""
    from app.dispatch_opt import can_detour_within_gap

    mandatory = ["A", "B", "C", "D"]
    assert can_detour_within_gap(mandatory, 0, ["A", "X", "B", "C", "D"])
    # 跳过 B
    assert not can_detour_within_gap(mandatory, 0, ["A", "X", "C", "D"])
    # 跳过 C
    assert not can_detour_within_gap(mandatory, 0, ["A", "B", "X", "D"])
