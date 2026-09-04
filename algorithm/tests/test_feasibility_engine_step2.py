"""Step2 回归：FeasibilityEngine PRELOADED/CargoLoad/CargoOut/CargoIn/duration+service/RETURN 末位。

口径以 OR-Tools baseline / validators 为唯一参考（不允许 HACO 另搞一套 cargo 规则）。
"""

import pytest

from app.haco.encoding import TaskBlock, TaskType
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.route_genome import EventType, RouteGenome
from app.models import CargoSource, Station


def _passenger(tid, pickup, delivery):
    return TaskBlock(task_id=tid, task_type=TaskType.PASSENGER,
                     pickup_station=pickup, delivery_station=delivery, size=1, order_ids=[tid])


def _shipment(tid, pickup, delivery, size=1):
    return TaskBlock(task_id=tid, task_type=TaskType.SHIPMENT,
                     pickup_station=pickup, delivery_station=delivery, size=size, order_ids=[tid])


def _delivery(tid, station, size=1, cargo_source=None):
    return TaskBlock(task_id=tid, task_type=TaskType.DELIVERY,
                     pickup_station=station, delivery_station=station, size=size,
                     order_ids=[tid], cargo_source=cargo_source)


def _pickup(tid, station, size=1):
    return TaskBlock(task_id=tid, task_type=TaskType.PICKUP,
                     pickup_station=station, delivery_station=station, size=size, order_ids=[tid])


class TestPreloadedCargo:
    def test_preloaded_within_initial_cargo_is_feasible(self):
        """PRELOADED 派送量 ≤ initial_cargo_load，不进 CargoLoad，容量两向复用。"""
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        d1 = _delivery("D1", "S1", size=3, cargo_source=CargoSource.PRELOADED)
        k1 = _pickup("K1", "S2", size=4)
        g.insert_task(d1, 1)
        g.insert_task(k1, 3)
        tasks = {t.task_id: t for t in (d1, k1)}
        engine = FeasibilityEngine()
        # initial_cargo_load=5 满装预装件出程，返程仍可揽收 4（两向复用）
        result = engine.check(g, tasks, passenger_capacity=5, cargo_capacity=5,
                              initial_cargo_load=5)
        assert result.feasible is True

    def test_preloaded_over_initial_cargo_infeasible(self):
        """PRELOADED 派送总量 > initial_cargo_load → PRELOAD_INSUFFICIENT。

        (CargoOut=5 未超容量 5，仅 preloaded 5 > initial 4 触发。)
        """
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        d1 = _delivery("D1", "S1", size=5, cargo_source=CargoSource.PRELOADED)
        g.insert_task(d1, 1)
        engine = FeasibilityEngine()
        result = engine.check(g, {d1.task_id: d1}, passenger_capacity=5, cargo_capacity=5,
                              initial_cargo_load=4)
        assert result.feasible is False
        assert result.reason_code == "PRELOAD_INSUFFICIENT"

    def test_plain_delivery_not_preloaded_does_not_consume_initial(self):
        """cargo_source=None 的 DELIVERY 不计 preloaded（旧逻辑），但计入 CargoOut。"""
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        d1 = _delivery("D1", "S1", size=3, cargo_source=None)
        g.insert_task(d1, 1)
        engine = FeasibilityEngine()
        result = engine.check(g, {d1.task_id: d1}, passenger_capacity=5, cargo_capacity=5,
                              initial_cargo_load=0)
        assert result.feasible is True


class TestReturnTerminalEngine:
    def test_engine_rejects_business_after_return(self):
        g = RouteGenome(0, 1, "D", skeleton=["S1"])
        p = _passenger("P1", "S1", "S2")
        g.insert_task(p, 1, 2)
        # 篡改：把 RETURN 移到最前，形成 RETURN 后仍有业务事件
        ret_idx = [i for i, e in enumerate(g.events) if e.event_type == EventType.RETURN][0]
        ret = g.events.pop(ret_idx)
        g.events.insert(0, ret)
        engine = FeasibilityEngine()
        result = engine.check(g, {p.task_id: p}, passenger_capacity=5, cargo_capacity=5)
        assert result.feasible is False
        assert result.reason_code == "BUSINESS_EVENT_AFTER_RETURN"


class TestDurationWithService:
    @staticmethod
    def _stations():
        return {
            "D": Station(stationId="D", longitude=0.0, latitude=0.0),
            "S1": Station(stationId="S1", longitude=1.0, latitude=0.0),
        }

    def test_duration_exceeds_with_service(self):
        """行驶 120s + 服务 60s(DELIVER)=180s；max_duration=100 应报 TIME_WINDOW_EXCEEDED。"""
        g = RouteGenome(0, 1, "D")
        d1 = _delivery("D1", "S1", size=1)
        g.insert_task(d1, 1)
        matrix = {("D", "S1"): (1.0, 60.0), ("S1", "D"): (1.0, 60.0)}
        engine = FeasibilityEngine()
        result = engine.check(g, {d1.task_id: d1}, passenger_capacity=5, cargo_capacity=5,
                              station_map=self._stations(), matrix=matrix, max_duration=100.0)
        assert result.feasible is False
        assert result.reason_code == "TIME_WINDOW_EXCEEDED"

    def test_duration_within_budget_passes(self):
        g = RouteGenome(0, 1, "D")
        d1 = _delivery("D1", "S1", size=1)
        g.insert_task(d1, 1)
        matrix = {("D", "S1"): (1.0, 60.0), ("S1", "D"): (1.0, 60.0)}
        engine = FeasibilityEngine()
        result = engine.check(g, {d1.task_id: d1}, passenger_capacity=5, cargo_capacity=5,
                              station_map=self._stations(), matrix=matrix, max_duration=1000.0)
        assert result.feasible is True
