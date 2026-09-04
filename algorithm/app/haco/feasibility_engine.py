"""HACO-CPS 1.4.0 统一可行性引擎。

基于 RouteGenome 事件序列的约束检查（与 OR-Tools baseline / validators 业务口径一致）：
- RETURN 末位（RETURN 恒为最末事件，其后无业务事件）
- 前序约束（PICKUP before DELIVER / BOARD before ALIGHT）
- 骨架顺序约束
- 乘客容量约束
- 货物容量约束（CargoLoad/CargoOut/CargoIn/PRELOADED，见 _check_cargo_capacity 注释）
- 时间窗约束（含服务时间，可选：须传 max_duration）
"""

from __future__ import annotations

from dataclasses import dataclass

from ..models import CargoSource, StopAction
from ..validators import service_duration
from .encoding import TaskBlock, TaskType
from .route_genome import (
    EventType,
    RouteGenome,
)

# 事件类型 → StopAction（service_duration 的唯一权威来源在 validators）。
# DEPOT 与 StopAction.DEPART 语义等价（出站无服务时间），其余事件名一一对应。
_EVENT_TO_ACTION = {
    EventType.DEPOT: StopAction.DEPART,
    EventType.PASS: StopAction.PASS,
    EventType.BOARD: StopAction.BOARD,
    EventType.ALIGHT: StopAction.ALIGHT,
    EventType.PICKUP: StopAction.PICKUP,
    EventType.DELIVER: StopAction.DELIVER,
    EventType.RETURN: StopAction.RETURN,
}


@dataclass(frozen=True)
class FeasibilityResult:
    feasible: bool
    reason_code: str | None = None


class FeasibilityEngine:
    """统一可行性引擎。

    可选实例级默认值：构造时若传入 max_duration/station_map/matrix，
    则 check() 在调用方未显式提供时使用它们。这样时间窗（含服务时间）约束
    能渗透进构造 / 修复 / 局部搜索的每一次内部 check，而不必逐层改签名。
    """

    def __init__(
        self,
        *,
        station_map=None,
        matrix=None,
        max_duration: float | None = None,
    ):
        self._station_map = station_map
        self._matrix = matrix
        self._max_duration = max_duration

    def check(
        self,
        route: RouteGenome,
        tasks_by_id: dict[str, TaskBlock],
        passenger_capacity: int,
        cargo_capacity: int,
        initial_passenger_load: int = 0,
        initial_cargo_load: int = 0,
        station_map=None,
        matrix=None,
        max_duration: float | None = None,
    ) -> FeasibilityResult:

        if station_map is None:
            station_map = self._station_map
        if matrix is None:
            matrix = self._matrix
        if max_duration is None:
            max_duration = self._max_duration

        checks = (
            self._check_terminal_return(route),
            self._check_precedence(route),
            self._check_skeleton(route),
            self._check_passenger_capacity(
                route,
                passenger_capacity,
                initial_passenger_load,
            ),
            self._check_cargo_capacity(
                route,
                tasks_by_id,
                cargo_capacity,
                initial_cargo_load,
            ),
        )

        for result in checks:
            if not result.feasible:
                return result

        if (
            max_duration is not None
            and station_map is not None
        ):
            result = self._check_duration(
                route,
                station_map,
                matrix,
                max_duration,
            )

            if not result.feasible:
                return result

        return FeasibilityResult(True)

    @staticmethod
    def _check_terminal_return(
        route: RouteGenome,
    ) -> FeasibilityResult:

        ok, reason = route.validate_terminal_return()

        return FeasibilityResult(
            feasible=ok,
            reason_code=reason,
        )

    @staticmethod
    def _check_precedence(
        route: RouteGenome,
    ) -> FeasibilityResult:

        ok, reason = route.validate_precedence()

        return FeasibilityResult(
            feasible=ok,
            reason_code=reason,
        )

    @staticmethod
    def _check_skeleton(
        route: RouteGenome,
    ) -> FeasibilityResult:

        ok, reason = route.validate_skeleton()

        return FeasibilityResult(
            feasible=ok,
            reason_code=reason,
        )

    @staticmethod
    def _check_passenger_capacity(
        route: RouteGenome,
        capacity: int,
        initial_load: int,
    ) -> FeasibilityResult:

        load = initial_load

        for event in route.events:

            if event.event_type == EventType.BOARD:
                load += 1

            elif event.event_type == EventType.ALIGHT:
                load -= 1

            if load < 0:
                return FeasibilityResult(
                    False,
                    "PASSENGER_LOAD_NEGATIVE",
                )

            if load > capacity:
                return FeasibilityResult(
                    False,
                    "PASSENGER_CAPACITY_EXCEEDED",
                )

        return FeasibilityResult(True)

    @staticmethod
    def _check_cargo_capacity(
        route: RouteGenome,
        tasks_by_id: dict[str, TaskBlock],
        capacity: int,
        initial_load: int,
    ) -> FeasibilityResult:
        """货物容量检查，口径与 baseline OR-Tools / validators 一致：

        - CargoLoad（车内真实货物）：以 initial_cargo_load 起步；
          SHIPMENT PICKUP +size、DELIVER -size；PRELOADED DELIVERY 与
          standalone PICKUP/DELIVERY 不进 CargoLoad（与 OR-Tools cargo_load_demand 相同）。
        - CargoOut（出程派送累计）：所有 DELIVER（含 SHIPMENT 送达与 PRELOADED）累计 ≤ capacity。
        - CargoIn（返程揽收累计）：所有 PICKUP（含 SHIPMENT 揽收）累计 ≤ capacity。
        - PRELOADED 派送总量 ≤ initial_cargo_load，否则 PRELOAD_INSUFFICIENT。
        """
        cargo_load = initial_load
        cargo_out = 0
        cargo_in = 0
        preloaded_delivered = 0

        for event in route.events:

            if not event.task_id:
                continue

            task = tasks_by_id[event.task_id]

            if task.task_type == TaskType.SHIPMENT:

                if event.event_type == EventType.PICKUP:
                    cargo_load += task.size
                    cargo_in += task.size

                elif event.event_type == EventType.DELIVER:
                    cargo_load -= task.size
                    cargo_out += task.size

            elif task.task_type == TaskType.DELIVERY:

                if event.event_type == EventType.DELIVER:
                    cargo_out += task.size

                    if (
                        task.cargo_source
                        == CargoSource.PRELOADED
                    ):
                        preloaded_delivered += task.size

            elif task.task_type == TaskType.PICKUP:

                if event.event_type == EventType.PICKUP:
                    cargo_in += task.size

            if cargo_load < 0:
                return FeasibilityResult(
                    False,
                    "CARGO_LOAD_NEGATIVE",
                )

            if cargo_load > capacity:
                return FeasibilityResult(
                    False,
                    "CARGO_CAPACITY_EXCEEDED",
                )

        if cargo_out > capacity:
            return FeasibilityResult(
                False,
                "CARGO_OUT_CAPACITY_EXCEEDED",
            )

        if cargo_in > capacity:
            return FeasibilityResult(
                False,
                "CARGO_IN_CAPACITY_EXCEEDED",
            )

        if preloaded_delivered > initial_load:
            return FeasibilityResult(
                False,
                "PRELOAD_INSUFFICIENT",
            )

        return FeasibilityResult(True)

    @staticmethod
    def _check_duration(
        route,
        station_map,
        matrix,
        max_duration,
    ):
        """总耗时 = 各段行驶时间 + 每站服务时间，口径同 validators.validate_time_window。"""
        from .heuristic import compute_duration

        total = 0.0

        for event in route.events:
            total += service_duration(
                _EVENT_TO_ACTION.get(
                    event.event_type,
                    StopAction.PASS,
                )
            )

        for i in range(1, len(route.events)):
            previous = route.events[i - 1]
            current = route.events[i]

            a = station_map[previous.station_id]
            b = station_map[current.station_id]

            total += compute_duration(
                a,
                b,
                matrix,
            )

        if total > max_duration:
            return FeasibilityResult(
                False,
                "TIME_WINDOW_EXCEEDED",
            )

        return FeasibilityResult(True)
