"""P0/P1 验收：真实道路 / 无 Teacher 泄漏 / 动态 K / hard-neg 两类。"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.haco.encoding import ObjectiveVector
from app.routing import estimated_only, is_formal_geometry
from learning.training import (
    CandidateRanker,
    FEATURE_NAMES,
    HardNegativeMiner,
    RankingDatasetBuilder,
    SearchTraceRecorder,
    TrainingDataQualityChecker,
    TrainingSampleGenerator,
)
from learning.training.search_space_reducer import (
    DynamicSearchSpaceReducer,
    offline_reduction_metrics,
)


def _sample(sid, cid, **kw):
    rec = SearchTraceRecorder(graph_version="g1", route_version="v1")
    return rec.record(
        search_state_id=sid, scenario_id="sc", iteration=1, vehicle_id=1,
        trip_id="R1", candidate_id=cid, candidate_operation="INSERT",
        candidate_type="CURRENT_TRIP", features=kw.pop("features", {"roadDistance": 100}),
        objective=kw.pop("objective", ObjectiveVector(total_distance=100)),
        **kw,
    )


def test_no_synthetic_training_data():
    """正式生成必须走 LocalRoutingEngine，不得 road=straight*random。"""
    src = Path("learning/training/generate_training_samples.py").read_text(encoding="utf-8")
    assert "straight * rng.uniform" not in src
    assert "rng.uniform(1.05, 2.5)" not in src
    assert "RealRoadRouter" in src
    assert "is_formal_geometry" in src


def test_real_road_required_quarantines_estimated():
    checker = TrainingDataQualityChecker()
    s = _sample("s1", "c1", route_provider="haversine")
    s.route_provider = "haversine"
    good, rep = checker.filter([s], Path("_t_q1"))
    assert rep.bad == 1
    assert rep.reasons.get("estimated_or_uncertain_route")


def test_group_size_metric_guard_dynamic_k():
    """k >= group_size → None，不得算成 1.0。"""
    groups = []
    builder = RankingDatasetBuilder()
    samples = []
    for i in range(4):
        samples.append(_sample("g", f"c{i}", objective=ObjectiveVector(total_distance=10 * (i + 1))))
    g = builder.build(samples)[0]
    ranker = CandidateRanker()
    m = ranker.evaluate([g], k_list=(1, 3, 5, 10))
    assert m["best_candidate_recall"][5] is None
    assert m["best_candidate_recall"][10] is None
    assert m["best_candidate_recall"][1] is not None
    assert m["ranking_recall"][3] is not None


def test_ranking_recall_and_best_feasible_excludes_empty():
    builder = RankingDatasetBuilder()
    samples = [
        _sample("a", "x", feasible=True, objective=ObjectiveVector(infeasibility=1, total_distance=1)),
        _sample("a", "y", feasible=True, objective=ObjectiveVector(infeasibility=2, total_distance=1)),
    ]
    g = builder.build(samples)[0]
    ranker = CandidateRanker()
    m = ranker.evaluate([g], k_list=(1,))
    # 全不可行 → best_feasible 为 N/A（None），不是 1.0
    assert m["best_feasible_candidate_recall"][1] is None
    assert "ranking_recall" in m


def test_reducer_no_teacher_leakage():
    """production reduce() 签名禁止 teacher_top_id / feasible_ids。"""
    import inspect

    sig = inspect.signature(DynamicSearchSpaceReducer.reduce)
    assert "teacher_top_id" not in sig.parameters
    assert "feasible_ids" not in sig.parameters
    assert "protected_ids" in sig.parameters
    r = DynamicSearchSpaceReducer(None)
    out = r.reduce([f"c{i}" for i in range(20)], np.zeros((20, len(FEATURE_NAMES))))
    assert out.kept_ids


def test_reduction_recall_offline_only():
    ranker = CandidateRanker()
    ranker.fallback_mode = False
    # fake predict
    ranker.predict = lambda X: np.linspace(0, 1, X.shape[0])  # type: ignore
    reducer = DynamicSearchSpaceReducer(ranker, target_ratio=0.3)
    ids = [f"c{i}" for i in range(20)]
    X = np.random.RandomState(0).randn(20, len(FEATURE_NAMES))
    teacher_order = ids[::-1]  # worst first? use explicit
    teacher_order = sorted(ids, key=lambda x: int(x[1:]))  # c0 best
    feas = teacher_order[:5]
    red = offline_reduction_metrics(
        [(ids, X, teacher_order, feas, {})], reducer, k_list=(1, 3, 5, 10),
    )
    assert red.total_candidates == 20
    assert 0.0 <= red.weighted_reduction <= 0.5
    assert red.reduction_recall[5] is not None


def test_reducer_ratio_isolation():
    ranker = CandidateRanker()
    ranker.fallback_mode = False
    ranker.predict = lambda X: np.linspace(0, 1, X.shape[0])  # type: ignore
    r = DynamicSearchSpaceReducer(ranker, target_ratio=0.25)
    ids = [f"c{i}" for i in range(30)]
    X = np.zeros((30, len(FEATURE_NAMES)))
    r1 = r.reduce(ids, X)
    assert abs(r.target_ratio - 0.25) < 1e-9  # 不被 group A 修改
    r2 = r.reduce(ids, X)
    assert r1.reduction_ratio == r2.reduction_ratio


def test_hard_negative_two_types_after_training():
    miner = HardNegativeMiner()
    samples = [
        _sample("s", f"c{i}", features={"roadDetourRatio": 1.01, "passengerImpact": 50},
                objective=ObjectiveVector(total_distance=10 * (i + 1)))
        for i in range(10)
    ]
    scores = np.linspace(0, 1, 10)
    md = miner.mine_model_disagreement(samples, scores)
    bd = miner.mine_boundary(samples)
    assert isinstance(md, list)
    assert isinstance(bd, list)
    assert miner.last_model_disagreement is not None
    assert miner.last_boundary is not None


def test_hard_negative_ratio_cap():
    miner = HardNegativeMiner(max_hard_negative_ratio=0.30)
    base = [_sample("b", f"b{i}") for i in range(10)]
    for i in range(50):
        miner.pool.add(_sample("h", f"h{i}", objective=ObjectiveVector(infeasibility=1)))
    combined = miner.sample_for_training(base)
    # hard neg added <= 30% of base
    assert len(combined) - len(base) <= int(len(base) * 0.30) + 1
    assert miner.backlog


def test_feature_leakage_rejected():
    s = _sample("s", "c")
    s.features["final_objective"] = 1.0
    checker = TrainingDataQualityChecker()
    good, rep = checker.filter([s], Path("_t_q2"))
    assert rep.bad == 1
    assert rep.reasons.get("feature_leakage")


def test_group_split_no_candidate_leakage():
    builder = RankingDatasetBuilder()
    samples = []
    for i in range(40):
        s = _sample(f"st{i // 4}", f"c{i}")
        s.scenario_id = f"st{i // 4}"
        samples.append(s)
    groups = builder.build(samples)
    tr, va, te = builder.split_by_group(groups)

    def keys(gs):
        return {(g.scenario_id, g.route_pattern, g.random_seed) for g in gs}

    assert not (keys(tr) & keys(te))
    assert not (keys(tr) & keys(va))


def test_multileg_training_requires_real_road():
    est = estimated_only(100, 100)
    assert not is_formal_geometry(est.status)
    s = _sample("s", "c", route_provider="local_graph")
    s.route_provider = "uncertain"
    rep = TrainingDataQualityChecker().filter([s], Path("_t_q3"))[1]
    assert rep.bad == 1
