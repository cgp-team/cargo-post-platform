"""LSR 2.0 训练管线测试。"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.haco.encoding import ObjectiveVector
from app.routing.local_routing import RoadEdge, RoadGraph, haversine_m
from learning.training import (
    AMapQuotaScheduler,
    CandidateRanker,
    DynamicSearchSpaceReducer,
    FEATURE_NAMES,
    HardNegativeMiner,
    ModelRegistry,
    RankingDatasetBuilder,
    SearchTraceRecorder,
    TrainingDataQualityChecker,
    TrainingSampleGenerator,
)
from learning.training.generate_training_samples import REAL_STATIONS, RealRoadRouter


def _fixture_graph() -> RoadGraph:
    """真实坐标站点 + 连通局部路网。

    用途仅限训练管线契约（无泄漏/分组切分/质量隔离）；不产出正式路网指标。
    """
    g = RoadGraph()
    names = sorted(REAL_STATIONS)
    for n in names:
        la, lo = REAL_STATIONS[n]
        g.add_node(n, la, lo)
    for i, a in enumerate(names):
        b = names[(i + 1) % len(names)]
        la1, lo1 = REAL_STATIONS[a]
        la2, lo2 = REAL_STATIONS[b]
        d = haversine_m((la1, lo1), (la2, lo2)) * 1.25
        g.add_edge(RoadEdge(a, b, d, d / 8.0, ((la1, lo1), (la2, lo2))))
    return g


def _pipeline_generator() -> TrainingSampleGenerator:
    return TrainingSampleGenerator(
        router=RealRoadRouter(graph=_fixture_graph()),
        stations=dict(REAL_STATIONS),
    )


def test_search_trace_recorder_ranks_by_objective():
    rec = SearchTraceRecorder(graph_version="g1")
    sid = rec.begin_state("sc", 1, 1, "R1", 1)
    rec.record(
        search_state_id=sid, scenario_id="sc", iteration=1, vehicle_id=1,
        trip_id="R1", candidate_id="a", candidate_operation="INSERT",
        candidate_type="CURRENT_TRIP", features={"roadDistance": 100},
        objective=ObjectiveVector(total_distance=100), feasible=True,
    )
    rec.record(
        search_state_id=sid, scenario_id="sc", iteration=1, vehicle_id=1,
        trip_id="R1", candidate_id="b", candidate_operation="INSERT",
        candidate_type="NEXT_TRIP", features={"roadDistance": 50},
        objective=ObjectiveVector(total_distance=50), feasible=True,
    )
    rec.assign_objective_ranks()
    ranks = {s.candidate_id: s.objective_rank for s in rec.samples}
    assert ranks["b"] == 1
    assert ranks["a"] == 2
    assert set(rec.samples[0].features) == set(FEATURE_NAMES)


@pytest.mark.slow
def test_training_label_generation_and_no_leakage():
    rec = _pipeline_generator().generate(200, seed=7)
    builder = RankingDatasetBuilder()
    groups = builder.build(rec.samples)
    assert groups
    tr, va, te = builder.split_by_group(groups)

    def keys(gs):
        return {(g.scenario_id, g.route_pattern, g.random_seed) for g in gs}

    assert not (keys(tr) & keys(te))
    assert "total_distance" not in builder.feature_names


@pytest.mark.slow
def test_candidate_ranker_trains_and_fallback():
    # 每个 search_state 一个 group；fixture 全 formal 时 group 数 ≈ samples/cands
    rec = _pipeline_generator().generate(240, seed=11)
    groups = RankingDatasetBuilder().build(rec.samples)
    assert len(groups) >= 4
    ranker = CandidateRanker()
    assert ranker.fallback_mode
    split = max(1, len(groups) - 1)
    valid = groups[split:] or groups[-1:]
    ranker.train(groups[:split], valid, n_estimators=30, early_stopping_rounds=5)
    assert not ranker.fallback_mode
    X = groups[0].X
    scores = ranker.predict(X)
    assert scores.shape[0] == X.shape[0]
    metrics = ranker.evaluate(groups[: min(3, len(groups))])
    assert 0.0 <= metrics["overall_feasible_candidate_recall"] <= 1.0
    assert "best_feasible_candidate_recall" in metrics


def test_search_reducer_protects_and_gates():
    ranker = CandidateRanker()
    ranker.fallback_mode = True
    r = DynamicSearchSpaceReducer(ranker)
    X = np.zeros((5, len(FEATURE_NAMES)))
    out = r.reduce([f"c{i}" for i in range(5)], X)
    assert out.reduction_ratio == 0.0
    assert list(out.kept_ids) == [f"c{i}" for i in range(5)]
    assert out.dropped_ids == []


@pytest.mark.slow
def test_hard_negative_mining_dedup():
    rec = _pipeline_generator().generate(150, seed=5)
    assert rec.samples
    miner = HardNegativeMiner(max_pool_size=10)
    miner.mine(rec.samples)
    combined = miner.combined_dataset(rec.samples)
    ids = [f"{s.search_state_id}|{s.candidate_id}" for s in combined]
    assert len(ids) == len(set(ids))


def test_model_registry_states(tmp_path):
    reg = ModelRegistry(tmp_path / "models")
    reg.register("m1", model_version="v1", metrics={"rec": 0.99})
    reg.mark("m1", "VALIDATED")
    assert reg.promote("m1")
    assert reg.active()["id"] == "m1"


@pytest.mark.slow
def test_data_quality_quarantine(tmp_path):
    rec = _pipeline_generator().generate(50, seed=9)
    samples = list(rec.samples)
    assert samples
    samples[0].features["roadDistance"] = float("nan")
    good, rep = TrainingDataQualityChecker().filter(samples, tmp_path / "q")
    assert rep.bad >= 1
    assert (tmp_path / "q" / "quarantine.jsonl").exists()


def test_amap_quota():
    q = AMapQuotaScheduler(daily_budget=10, reserve=2, target_use=8)
    for _ in range(8):
        assert q.try_consume("low")
    assert not q.try_consume("low")
    assert q.try_consume("high")


def test_multileg_real_road_cost_not_haversine_formal():
    from app.routing import estimated_only, is_formal_geometry
    from app.routing.real_road_cost import RealRoadMarginalCostEvaluator
    from app.routing import GeometryStatus, RouteGeometryResult

    est = estimated_only(100, 100)
    assert not is_formal_geometry(est.status)
    formal = RouteGeometryResult(
        available=True, distance_m=5000, duration_s=300,
        polyline=((0, 0), (0.01, 0.01)),
        status=GeometryStatus.LOCAL_ROAD, straight_distance_m=2000,
    )
    cost = RealRoadMarginalCostEvaluator().evaluate(
        baseline=formal, candidate=est, economic_value=10
    )
    assert cost.geometry_formal is False
