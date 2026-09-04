"""Step3 回归：1.4 Objective 六维严格 lexicographic + 由 RouteGenome.events 计算真实指标。

锁定的不变量（防止回归成"weighted scalar 改变业务排序 / cargo_detour≡0 / passenger_impact≡0"）：
1. ObjectiveVector.key() 维度顺序为
   infeasibility > vehicle_count > passenger_impact > cargo_detour > total_distance > total_duration，
   且任意前一维严格支配后一维（不因后几维更优而翻转）。
2. 车上载客时货物绕行 → cargo_detour>0 且 passenger_impact>0（由 events 真实计算，非占位）。
"""

import pytest

from app.haco.encoding import ObjectiveVector, TaskBlock, TaskType
from app.haco.evaluator import evaluate_route_genome
from app.haco.route_genome import RouteGenome


class FakeStation:
    def __init__(self, sid, lon, lat):
        self.stationId = sid
        self.longitude = lon
        self.latitude = lat


def _station_map():
    return {
        s: FakeStation(s, *c) for s, c in {
            "D": (0.0, 0.0), "S1": (0.01, 0.0), "S2": (0.02, 0.0),
            "S3": (0.03, 0.0), "S4": (0.04, 0.0),
        }.items()
    }


def _passenger(tid, p, d):
    return TaskBlock(task_id=tid, task_type=TaskType.PASSENGER,
                     pickup_station=p, delivery_station=d, size=1, order_ids=[tid])


def _shipment(tid, p, d, size=1):
    return TaskBlock(task_id=tid, task_type=TaskType.SHIPMENT,
                     pickup_station=p, delivery_station=d, size=size, order_ids=[tid])


def _obj(**kw):
    base = dict(infeasibility=0.0, vehicle_count=1, passenger_impact=0.0,
                cargo_detour=0.0, total_distance=0.0, total_duration=0.0)
    base.update(kw)
    return ObjectiveVector(**base)


class TestStrictLexicographic:
    def test_infeasibility_dominates_all(self):
        assert _obj(infeasibility=1.0, vehicle_count=1) > _obj(infeasibility=0.0, vehicle_count=99)

    def test_vehicle_count_dominates_passenger_impact(self):
        # 车辆更少的解更优，即使其 passenger_impact 巨大
        assert _obj(vehicle_count=2, passenger_impact=1e9) < _obj(vehicle_count=3, passenger_impact=0.0)

    def test_passenger_impact_dominates_cargo_detour(self):
        # 乘客影响更小的解更优，即使 cargo_detour 大得多
        assert _obj(passenger_impact=0.0, cargo_detour=1e9) < _obj(passenger_impact=1.0, cargo_detour=0.0)

    def test_cargo_detour_dominates_distance(self):
        assert _obj(cargo_detour=1.0, total_distance=0.0) > _obj(cargo_detour=0.0, total_distance=1e9)

    def test_distance_dominates_duration(self):
        assert _obj(total_distance=1.0, total_duration=0.0) < _obj(total_distance=2.0, total_duration=0.0)

    def test_equal_dimensions_equal(self):
        assert _obj(passenger_impact=1.5) == _obj(passenger_impact=1.5)


class TestEventsBasedMetrics:
    def test_cargo_detour_and_passenger_impact_nonzero(self):
        """车上载客 + 货物绕行 → 两个指标都由事件序列真实算出，非占位 0。"""
        sm = _station_map()
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2", "S3"])
        p = _passenger("P1", "S1", "S3")
        s = _shipment("T1", "S1", "S2", size=1)
        g.insert_task(p, 1, 4)
        g.insert_task(s, 2, 5)
        tasks = {t.task_id: t for t in (p, s)}

        metrics = evaluate_route_genome(g, tasks, sm, None)
        assert metrics["distance"] > 0
        assert metrics["cargo_detour"] > 0, f"cargo_detour should be >0, got {metrics['cargo_detour']}"
        assert metrics["passenger_impact"] > 0, f"passenger_impact should be >0, got {metrics['passenger_impact']}"

    def test_no_passenger_no_impact(self):
        sm = _station_map()
        g = RouteGenome(0, 1, "D", skeleton=["S1", "S2"])
        s = _shipment("T1", "S1", "S2", size=1)
        g.insert_task(s, 1, 3)
        tasks = {s.task_id: s}
        metrics = evaluate_route_genome(g, tasks, sm, None)
        assert metrics["passenger_impact"] == 0.0
