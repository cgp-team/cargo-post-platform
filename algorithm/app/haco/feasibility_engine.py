"""HACO-CPS 1.4.0 统一可行性引擎。

基于 RouteGenome 事件序列的约束检查：
- 前序约束（PICKUP before DELIVER）
- 骨架顺序约束
- 乘客容量约束
- 货物容量约束（区分出程/返程）
- 时间窗约束（可选）
"""

from __future__ import annotations

from dataclasses import dataclass

from .encoding import TaskBlock, TaskType
from .route_genome import (
    EventType,
    RouteGenome,
)


@dataclass(frozen=True)
class FeasibilityResult:
    feasible: bool
    reason_code: str | None = None


class FeasibilityEngine:

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

        checks = (
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

        # Shipment / 真正车内货物
        cargo_load = initial_load

        # 独立派送 / 揽收分别累计
        cargo_out = 0
        cargo_in = 0

        for event in route.events:

            if not event.task_id:
                continue

            task = tasks_by_id[event.task_id]

            if task.task_type == TaskType.SHIPMENT:

                if event.event_type == EventType.PICKUP:
                    cargo_load += task.size

                elif event.event_type == EventType.DELIVER:
                    cargo_load -= task.size

            elif task.task_type == TaskType.DELIVERY:

                if event.event_type == EventType.DELIVER:
                    cargo_out += task.size

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

        return FeasibilityResult(True)

    @staticmethod
    def _check_duration(
        route,
        station_map,
        matrix,
        max_duration,
    ):
        from .heuristic import compute_duration

        total = 0.0

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
