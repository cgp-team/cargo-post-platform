"""MarginalCostEvaluator 测试。"""

from app.dispatch_opt.marginal_cost import DefaultCostModel, MarginalCostEvaluator
from app.dispatch_opt.models import HandoverCostBreakdown


def test_delta_distance_cost():
    ev = MarginalCostEvaluator()
    b = ev.evaluate(delta_distance_m=2000)
    assert b.delta_distance_m == 2000
    assert b.distance_cost > 0
    assert b.total_incremental_cost > 0


def test_delta_duration_and_passenger_impact():
    ev = MarginalCostEvaluator()
    b = ev.evaluate(delta_duration_s=300, delta_passenger_impact_s=120)
    assert b.time_cost > 0
    assert b.passenger_impact_cost > 0
    assert b.delta_passenger_impact_s == 120


def test_handover_and_waiting_costs():
    ev = MarginalCostEvaluator()
    hb = HandoverCostBreakdown(
        transfer_distance_m=50, dwell_time_s=600, waiting_time_s=300, operational_cost=1.5
    )
    b = ev.evaluate(
        handover_count=1,
        handover_breakdown=hb,
        delta_waiting_s=600,
        delta_delay_risk=0.2,
    )
    assert b.handover_cost > 0
    assert b.waiting_cost > 0
    assert b.risk_cost > 0
    assert b.delta_handover_count == 1


def test_efficiency_optional_not_faked():
    ev = MarginalCostEvaluator()
    assert ev.efficiency(None, 5.0) is None
    assert ev.efficiency(10.0, 4.0) == 2.5
    assert ev.efficiency(10.0, 0) is None


def test_cost_model_replaceable():
    model = DefaultCostModel(yuan_per_km=0.0, yuan_per_minute=0.0)
    ev = MarginalCostEvaluator(model)
    b = ev.evaluate(delta_distance_m=1000, delta_duration_s=60)
    assert b.distance_cost == 0.0
    assert b.time_cost == 0.0
