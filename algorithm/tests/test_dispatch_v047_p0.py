"""DISPATCH_CORE_V047 P0 回归：候选级调度、真实成本、TripLock、MultiLeg、Decision Trace。

覆盖 section 36（场景 1–30）与 section 37（重点反例 CASE 1–10）。
"""

from __future__ import annotations

import pytest

from app.dispatch_opt import (
    CandidateType,
    DecisionTrace,
    DispatchOrder,
    DispatchRequest,
    DispatchTripPayload,  # noqa: F401  (re-export smoke)
    DynamicDispatchCoordinator,
    EconomicAdmission,
    EconomicPolicy,
    EconomicPolicyInput,
    FlexibilityInput,
    FlexibilityLevel,
    TripView,
    classify_candidate_reachability,
    estimate_flexibility,
)
from app.dispatch_opt.candidate_builder import DispatchCandidateBuilder
from app.dispatch_opt.handover import ChainLegResult, TransportChainEvaluator, can_handover
from app.dispatch_opt.models import TripCandidate, TripExecutionState
from app.dispatch_opt.reachability import (
    CandidateReachabilityInput,
    ReachabilityStatus,
)
from app.dispatch_opt.route_cost_provider import (
    GapRoutePair,
    RouteCost,
    RouteCostStatus,
    build_gap_waypoints,
)
from app.dispatch_opt.segment_completion import (
    OrderStatus,
    SegmentStatus,
    TaskSegmentCompletionValidator,
)
from app.dispatch_opt.trip_lock import TripLockPolicy


# ── 测试夹具：正式道路 provider stub（unit test stub 允许固定值） ──
class FormalStubProvider:
    """仅用于单测：显式声明 formal，避免触发真实 GraphHopper/AMap。"""

    def __init__(self, distance_m: float = 200.0, duration_s: float = 30.0):
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


# PU 明显偏离 origin→DE 直线，确保 "单车完成 pickup+delivery" 的绕行很大
COORDS = {"PU": (30.06, 104.0), "DE": (30.0, 104.06)}


def _order(**kw) -> DispatchOrder:
    base = dict(
        order_id="O1",
        pickup_service_point="PU",
        delivery_service_point="DE",
        quantity=1,
        economic_value=50.0,
        delivery_deadline=100_000.0,
    )
    base.update(kw)
    return DispatchOrder(**base)


def _trip(
    vehicle_id: int = 1,
    route_id: str = "347",
    shift_id: str = "07:30",
    *,
    departure_time: float = 0.0,
    state: TripExecutionState = TripExecutionState.PLANNED,
    location=(30.0, 104.0),
    cargo: int = 3,
    detour_budget: float = 5_000.0,
    pax_budget: float = 100_000.0,
    **kw,
) -> TripView:
    return TripView(
        route_id=route_id,
        shift_id=shift_id,
        vehicle_id=vehicle_id,
        departure_time=departure_time,
        execution_state=state,
        current_location=location,
        remaining_cargo_capacity=cargo,
        trip_detour_remaining_m=detour_budget,
        passenger_impact_budget_s=pax_budget,
        station_coords=COORDS,
        **kw,
    )


BIG_BUDGET = dict(detour_budget=50_000.0, pax_budget=1_000_000.0)


# ═══════════ section 36: 场景 1–6 ═══════════


def test_scenario_01_02_normal_order_current_trip_insertable():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(DispatchRequest(order=_order(), current_trips=[_trip(**BIG_BUDGET)]))
    assert plan.status == "ASSIGNED"
    assert plan.chosen.candidate_type is CandidateType.CURRENT_TRIP
    assert plan.level == "LEVEL_0_FAST_INSERT"


def test_scenario_03_current_capacity_full_goes_next_trip():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(cargo=0, **BIG_BUDGET)],
            future_trips=[_trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.NEXT_TRIP
    assert "CURRENT_TRIP" not in [
        r.candidate_type for r in plan.trace.records if r.feasible
    ]


def test_scenario_04_current_detour_budget_exceeded_goes_next():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(detour_budget=100.0, pax_budget=1_000_000.0)],
            future_trips=[
                _trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)
            ],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.NEXT_TRIP
    reasons = {r.candidate_type: r.reason_code for r in plan.trace.records}
    assert reasons["CURRENT_TRIP"] in ("DETOUR_TOO_LARGE", "DETOUR_BUDGET_EXCEEDED")


def test_scenario_05_current_passenger_impact_exceeded():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(passenger_count=10),
            current_trips=[
                _trip(
                    detour_budget=50_000.0,
                    pax_budget=0.0,
                    remaining_passenger_capacity=50,
                )
            ],
            future_trips=[
                _trip(
                    vehicle_id=2,
                    shift_id="08:00",
                    departure_time=1800,
                    remaining_passenger_capacity=50,
                    **BIG_BUDGET,
                )
            ],
        )
    )
    assert plan.chosen is not None
    assert plan.chosen.candidate_type is CandidateType.NEXT_TRIP


def test_scenario_06_current_sla_missed():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(delivery_deadline=1.0),  # 不可能按时送达
            current_trips=[_trip(**BIG_BUDGET)],
        )
    )
    reasons = [r.reason_code for r in plan.trace.records]
    assert "ETA_MISSED" in reasons
    assert plan.status == "HOLD"  # 不直接 REJECT，进入 HOLD/人工


# ═══════════ 场景 7–9: 发车后 TripLock ═══════════


def test_scenario_07_departed_normal_order_not_current():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(state=TripExecutionState.DEPARTED, **BIG_BUDGET)],
            future_trips=[
                _trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)
            ],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.NEXT_TRIP
    assert [r.reason_code for r in plan.trace.records if r.candidate_type == "CURRENT_TRIP"] == [
        "LOCKED_ACTIVE_TRIP"
    ]


def test_scenario_08_departed_high_value_low_impact_allows_realtime_insert():
    co = DynamicDispatchCoordinator(route_provider=FormalStubProvider(), now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(is_high_value=True, economic_value=300.0),
            current_trips=[_trip(state=TripExecutionState.DEPARTED, **BIG_BUDGET)],
        )
    )
    assert plan.chosen is not None
    assert plan.chosen.candidate_type is CandidateType.CURRENT_TRIP
    assert plan.level == "LEVEL_0_FAST_INSERT"


def test_scenario_09_departed_high_value_high_impact_rejected():
    co = DynamicDispatchCoordinator(route_provider=FormalStubProvider(), now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(is_high_value=True, passenger_count=5),
            current_trips=[
                _trip(
                    state=TripExecutionState.DEPARTED,
                    remaining_passenger_capacity=50,
                    **BIG_BUDGET,
                )
            ],
            max_passenger_impact_s=1.0,
        )
    )
    assert plan.status == "HOLD"
    assert [r.reason_code for r in plan.trace.records if r.candidate_type == "CURRENT_TRIP"] == [
        "PASSENGER_IMPACT_EXCEEDED"
    ]


# ═══════════ 场景 10–12: 逐级恢复 ═══════════


def test_scenario_10_current_infeasible_to_next_trip():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(cargo=0, **BIG_BUDGET)],
            future_trips=[
                _trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)
            ],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.NEXT_TRIP


def test_scenario_11_next_infeasible_to_other_route():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(cargo=0, **BIG_BUDGET)],
            future_trips=[_trip(vehicle_id=2, shift_id="08:00", departure_time=1800, cargo=0, **BIG_BUDGET)],
            other_route_trips=[
                _trip(vehicle_id=3, route_id="303", shift_id="07:50", departure_time=600, **BIG_BUDGET)
            ],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.OTHER_ROUTE


def test_scenario_12_other_route_then_multileg_chain():
    """DIRECT 全部不可行（绕行超预算），2-Leg 可行 → MULTILEG。"""
    co = DynamicDispatchCoordinator(now=0.0)
    leg1 = _trip(vehicle_id=1, detour_budget=1000.0, pax_budget=1_000_000.0)
    leg2 = _trip(
        vehicle_id=2,
        route_id="303",
        shift_id="08:00",
        departure_time=5000,
        location=(30.055, 104.005),
        detour_budget=1000.0,
        pax_budget=1_000_000.0,
    )
    plan = co.plan(
        DispatchRequest(order=_order(), current_trips=[leg1], future_trips=[leg1], other_route_trips=[leg2])
    )
    assert plan.chosen is not None
    assert plan.chosen.candidate_type is CandidateType.MULTILEG_2
    assert plan.chosen.handover_count == 1


# ═══════════ 场景 13–15: MultiLeg 直接/2-leg/3-leg ═══════════


def test_scenario_13_14_15_multileg_2_and_3_built_with_handover():
    b = DispatchCandidateBuilder(now=0.0)
    order = _order(quantity=1)
    a = _trip(vehicle_id=1, location=(30.0, 104.0), detour_budget=1000.0, pax_budget=1_000_000.0)
    bb = _trip(
        vehicle_id=2,
        route_id="303",
        shift_id="08:00",
        departure_time=5000,
        location=(30.055, 104.005),
        detour_budget=1000.0,
        pax_budget=1_000_000.0,
    )
    two = b.build_multileg([a], [bb], order)
    assert two and two[0].candidate_type is CandidateType.MULTILEG_2
    assert two[0].legs == ("347", "303")
    assert two[0].handover_count == 1

    cc = _trip(
        vehicle_id=3,
        route_id="305",
        shift_id="09:00",
        departure_time=3000,
        location=(30.055, 104.0),
        **BIG_BUDGET,
    )
    far = _trip(
        vehicle_id=2,
        route_id="303",
        shift_id="08:00",
        departure_time=7000,
        location=(30.055, 104.005),
        **BIG_BUDGET,
    )
    three = b.build_multileg([a, cc], [far], order)
    kinds = {c.candidate_type for c in three}
    assert CandidateType.MULTILEG_3 in kinds
    three_leg = next(c for c in three if c.candidate_type is CandidateType.MULTILEG_3)
    assert three_leg.handover_count == 2
    assert len(three_leg.legs) == 3


def test_handover_time_includes_travel_dwell_handling():
    """P0-5: 必须检查 arrival + travel + dwell + handling <= next departure。"""
    tight = can_handover(from_arrival_time=0.0, to_departure_time=1000.0)
    assert not tight.feasible
    assert tight.reason_code == "HANDOVER_INFEASIBLE"
    ok = can_handover(from_arrival_time=0.0, to_departure_time=5000.0)
    assert ok.feasible
    # travel 让门限更高
    far = can_handover(
        from_arrival_time=0.0, to_departure_time=1500.0, same_station=False, station_distance_m=120.0
    )
    assert not far.feasible


# ═══════════ 场景 16–21: reachability 变体 ═══════════


@pytest.mark.parametrize(
    "flags,expected_status",
    [
        (dict(road_reachable=False, has_transfer_option=True), ReachabilityStatus.TRANSFER_REQUIRED),
        (dict(road_reachable=False, has_transfer_option=False), ReachabilityStatus.UNREACHABLE),
        (dict(vehicle_access=False), ReachabilityStatus.NEAREST_STATION_REQUIRED),
        (dict(user_access=False), ReachabilityStatus.NEAREST_STATION_REQUIRED),
        (dict(already_passed=True), ReachabilityStatus.FUTURE_TRIP_REQUIRED),
        (dict(network_known=False), ReachabilityStatus.UNKNOWN),
        (dict(location_fresh=False), ReachabilityStatus.UNKNOWN),
        (dict(), ReachabilityStatus.REACHABLE),
    ],
)
def test_scenario_16_to_21_reachability_matrix(flags, expected_status):
    inp = CandidateReachabilityInput(
        vehicle_id=1,
        route_id="347",
        shift_id="07:30",
        execution_state=TripExecutionState.PLANNED,
        **flags,
    )
    assert classify_candidate_reachability(inp).status is expected_status


def test_scenario_21_nearest_legal_station_candidate_built():
    b = DispatchCandidateBuilder(now=0.0)
    station_trip = _trip(vehicle_id=9, **BIG_BUDGET)
    cands = b.build_nearest_station(station_trip, _order())
    assert len(cands) == 1
    assert cands[0].candidate_type is CandidateType.NEAREST_STATION


# ═══════════ 场景 24/26: 终态锁定 ═══════════


@pytest.mark.parametrize("state", [TripExecutionState.COMPLETED, TripExecutionState.FAILED])
def test_scenario_24_26_terminal_trip_never_inserted(state):
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(order=_order(is_high_value=True), current_trips=[_trip(state=state, **BIG_BUDGET)])
    )
    assert plan.chosen is None
    assert [r.reason_code for r in plan.trace.records if r.candidate_type == "CURRENT_TRIP"] == [
        "TRIP_TERMINAL"
    ]


# ═══════════ 场景 27/28: 事件驱动重规划入口 ═══════════


def test_scenario_27_28_replan_trigger_scope():
    from app.dispatch_opt.remaining_replan import (
        PlanSegment,
        ReplanRequest,
        ReplanTrigger,
        RemainingSegmentReplan,
    )

    rp = RemainingSegmentReplan()
    # 取消订单：只重排未执行/未锁定
    scope = rp.build_scope(
        ReplanRequest(
            trigger=ReplanTrigger.ORDER_CANCELLATION,
            segments=[PlanSegment("s1", locked=True), PlanSegment("s2"), PlanSegment("s3", completed=True)],
        )
    )
    assert scope.free_segment_ids == ["s2"]
    assert scope.completed_segment_ids == ["s3"]
    assert scope.locked_segment_ids == ["s1"]

    # 紧急订单且无 free 段 → 允许全局升级
    gscope = rp.build_scope(
        ReplanRequest(
            trigger=ReplanTrigger.EMERGENCY_ORDER,
            segments=[PlanSegment("s1", locked=True)],
        )
    )
    assert gscope.allow_global is True


# ═══════════ 场景 29/30: flexibility / 高价值保护 ═══════════


def test_scenario_29_low_flexibility_order():
    flex = estimate_flexibility(FlexibilityInput())
    assert flex.level is FlexibilityLevel.VERY_LOW
    assert flex.options == 0
    high = estimate_flexibility(
        FlexibilityInput(
            feasible_current_trips=2,
            feasible_future_trips=2,
            feasible_other_routes=2,
            feasible_multileg=1,
        )
    )
    assert high.level is FlexibilityLevel.HIGH


def test_scenario_30_high_value_low_flex_protected_but_transferable_not():
    from app.dispatch_opt.flexibility import should_protect_from_destroy

    low = estimate_flexibility(FlexibilityInput(feasible_future_trips=1))
    assert should_protect_from_destroy(low, high_value=True) is True
    transferable = estimate_flexibility(
        FlexibilityInput(feasible_other_routes=2, feasible_multileg=2, feasible_future_trips=2)
    )
    assert should_protect_from_destroy(transferable, high_value=True) is False
    assert should_protect_from_destroy(low, high_value=False) is False


# ═══════════ 数据缺失兼容 ═══════════


def test_economic_policy_without_value_still_works():
    pol = EconomicPolicy()
    d = pol.evaluate(EconomicPolicyInput(incremental_cost=999.0))
    assert d.admission is EconomicAdmission.ACCEPT
    assert d.reason_code == "FEASIBILITY_ONLY_NO_ECONOMIC_VALUE"


def test_economic_policy_bands():
    pol = EconomicPolicy(accept_ratio=2.0, defer_ratio=0.5)
    assert pol.evaluate(EconomicPolicyInput(economic_value=30.0, incremental_cost=10.0)).admission \
        is EconomicAdmission.ACCEPT
    assert pol.evaluate(EconomicPolicyInput(economic_value=8.0, incremental_cost=10.0)).admission \
        is EconomicAdmission.DEFER
    assert pol.evaluate(
        EconomicPolicyInput(economic_value=1.0, incremental_cost=10.0, handover_count=1)
    ).admission is EconomicAdmission.TRANSFER
    assert pol.evaluate(EconomicPolicyInput(economic_value=1.0, incremental_cost=10.0)).admission \
        is EconomicAdmission.REJECT


# ═══════════ section 37: 重点反例 CASE 1–10 ═══════════


def test_case_01_current_capacity_zero_never_current():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(cargo=0, **BIG_BUDGET)],
            future_trips=[_trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)],
        )
    )
    assert plan.chosen.candidate_type is not CandidateType.CURRENT_TRIP


def test_case_02_current_detour_budget_zero_never_current():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(detour_budget=0.0, pax_budget=1_000_000.0)],
            future_trips=[_trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)],
        )
    )
    assert plan.chosen.candidate_type is not CandidateType.CURRENT_TRIP


def test_case_03_pickup_reachable_delivery_eta_missed_is_infeasible():
    b = DispatchCandidateBuilder(now=0.0)
    cands = b.build_current([_trip(**BIG_BUDGET)], _order(delivery_deadline=1.0))
    assert cands[0].feasibility is False
    assert cands[0].reason_code == "ETA_MISSED"


def test_case_04_already_passed_corridor_not_selected():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(already_passed=True, **BIG_BUDGET)],
            future_trips=[_trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.NEXT_TRIP


def test_case_05_departed_low_value_high_impact_rejected():
    co = DynamicDispatchCoordinator(route_provider=FormalStubProvider(), now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(passenger_count=8),
            current_trips=[_trip(state=TripExecutionState.DEPARTED, **BIG_BUDGET)],
            max_passenger_impact_s=1.0,
        )
    )
    assert plan.status == "HOLD"


def test_case_06_departed_high_value_low_cost_sla_safe_allowed():
    co = DynamicDispatchCoordinator(route_provider=FormalStubProvider(), now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(is_high_value=True, economic_value=500.0),
            current_trips=[_trip(state=TripExecutionState.DEPARTED, **BIG_BUDGET)],
        )
    )
    assert plan.chosen.candidate_type is CandidateType.CURRENT_TRIP


def test_case_07_direct_infeasible_two_leg_feasible():
    co = DynamicDispatchCoordinator(now=0.0)
    leg1 = _trip(vehicle_id=1, detour_budget=1000.0, pax_budget=1_000_000.0)
    leg2 = _trip(
        vehicle_id=2,
        route_id="303",
        shift_id="08:00",
        departure_time=5000,
        location=(30.055, 104.005),
        detour_budget=1000.0,
        pax_budget=1_000_000.0,
    )
    plan = co.plan(
        DispatchRequest(order=_order(), current_trips=[leg1], future_trips=[leg1], other_route_trips=[leg2])
    )
    assert plan.chosen.candidate_type is CandidateType.MULTILEG_2


def test_case_08_leg1_arrived_leg2_incomplete_is_in_transit():
    chain = TransportChainEvaluator()
    r = chain.evaluate([ChainLegResult("L1", True), ChainLegResult("L2", False)], False)
    assert r.order_status == "IN_TRANSIT"
    seg = TaskSegmentCompletionValidator()
    assert (
        seg.order_status([SegmentStatus.SEGMENT_COMPLETED, SegmentStatus.IN_PROGRESS], False)
        is OrderStatus.IN_TRANSIT
    )
    assert (
        seg.order_status([SegmentStatus.SEGMENT_COMPLETED, SegmentStatus.SEGMENT_COMPLETED], True)
        is OrderStatus.ORDER_COMPLETED
    )


def test_case_09_gap_never_returns_to_b():
    mids = build_gap_waypoints(pickup=(30.06, 104.0), delivery=(30.0, 104.06))
    b = (30.0, 104.0)
    c = (30.0, 104.06)
    assert b not in mids  # 不允许 B 作为中间点（避免 B→X→Y→B→C）
    assert mids[0] == (30.06, 104.0)
    assert mids[-1] == (30.0, 104.06)

    prov = FormalStubProvider()
    pair = prov.route_gap(b, c, pickup=mids[0], delivery=mids[1])
    assert pair.waypoints == mids
    assert prov.route_candidate((b, *mids, c)).distance_m >= pair.baseline.distance_m


def test_case_10_lower_vehicle_count_does_not_auto_win():
    from app.dispatch_opt.comparator import compare_trip_candidates

    one_vehicle_high_impact = TripCandidate(
        source_kind="CURRENT_TRIP",
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        passenger_impact_s=90.0,
        incremental_cost=3.0,
        economic_value=10.0,
    ).with_efficiency()
    two_vehicle_low_impact = TripCandidate(
        source_kind="NEXT_TRIP",
        route_id="347",
        shift_id="08:00",
        vehicle_id=2,
        passenger_impact_s=10.0,
        incremental_cost=6.0,
        economic_value=10.0,
    ).with_efficiency()
    ranked = compare_trip_candidates([one_vehicle_high_impact, two_vehicle_low_impact])
    assert ranked[0].passenger_impact_s == 10.0
    assert ranked[0].vehicle_id == 2


# ═══════════ Decision Trace ═══════════


def test_decision_trace_explains_selection_and_rejections():
    co = DynamicDispatchCoordinator(now=0.0)
    plan = co.plan(
        DispatchRequest(
            order=_order(),
            current_trips=[_trip(cargo=0, **BIG_BUDGET)],
            future_trips=[_trip(vehicle_id=2, shift_id="08:00", departure_time=1800, **BIG_BUDGET)],
        )
    )
    assert plan.trace.selected is not None
    assert plan.trace.selected.selected is True
    assert "CURRENT_TRIP" in plan.trace.why_not_others()[0]
    payload = plan.trace.as_dict()
    assert payload["selectedCandidateId"] == plan.trace.selected.candidate_id
    assert payload["whySelected"]
    assert isinstance(payload["candidates"], list) and payload["candidates"]


def test_trip_lock_policy_unified_reason_codes():
    pol = TripLockPolicy()
    assert pol.decide_candidate(TripExecutionState.COMPLETED).reason_code == "TRIP_TERMINAL"
    assert pol.decide_candidate(TripExecutionState.PLANNED).allow_insert is True
    d = pol.decide_candidate(TripExecutionState.DEPARTED, is_high_value=True, cost_is_formal=False)
    assert not d.allow_insert and d.reason_code == "COST_NOT_FORMAL"
    d2 = pol.decide_candidate(
        TripExecutionState.DEPARTED,
        is_high_value=True,
        cost_is_formal=True,
        marginal_distance_m=10.0,
        marginal_duration_s=10.0,
        passenger_impact_s=1.0,
        economic_admission="DEFER",
    )
    assert not d2.allow_insert and d2.reason_code == "ECONOMIC_ADMISSION_DEFER"
