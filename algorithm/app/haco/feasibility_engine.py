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


@dataclass(frozen=True)
class FeasibilityContext:
    """Immutable hard-constraint context shared by every feasibility caller.

    Construction / ALNS / Local Search / Final Validation / Dynamic Dispatch must
    all go through the SAME hard-constraint wording (DISPATCH_CORE_V047 section 22).
    """

    station_map: dict | None = None
    matrix: object | None = None
    max_duration: float | None = None
    max_detour_km: float | None = None
    trip_detour_remaining_m: float | None = None
    expected_task_ids: set[str] | None = None
    # 重量/体积硬约束（未配置时 None = 不检查，兼容旧 itemCount 口径）
    cargo_weight_capacity_kg: float | None = None
    cargo_volume_capacity_m3: float | None = None

    def merged_into(self, engine: "FeasibilityEngine") -> "FeasibilityContext":
        return FeasibilityContext(
            station_map=self.station_map if self.station_map is not None else engine._station_map,
            matrix=self.matrix if self.matrix is not None else engine._matrix,
            max_duration=(
                self.max_duration if self.max_duration is not None else engine._max_duration
            ),
            max_detour_km=(
                self.max_detour_km
                if self.max_detour_km is not None
                else engine._max_detour_km
            ),
            trip_detour_remaining_m=(
                self.trip_detour_remaining_m
                if self.trip_detour_remaining_m is not None
                else engine._trip_detour_remaining_m
            ),
            expected_task_ids=self.expected_task_ids,
            cargo_weight_capacity_kg=(
                self.cargo_weight_capacity_kg
                if self.cargo_weight_capacity_kg is not None
                else engine._cargo_weight_capacity_kg
            ),
            cargo_volume_capacity_m3=(
                self.cargo_volume_capacity_m3
                if self.cargo_volume_capacity_m3 is not None
                else engine._cargo_volume_capacity_m3
            ),
        )


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
        max_detour_km: float | None = None,
        trip_detour_remaining_m: float | None = None,
        cargo_weight_capacity_kg: float | None = None,
        cargo_volume_capacity_m3: float | None = None,
        # 按车覆盖的重量/体积运力（key=vehicle_index）；未配置车辆不检查
        cargo_weight_capacities: dict[int, float] | None = None,
        cargo_volume_capacities: dict[int, float] | None = None,
        context: "FeasibilityContext | None" = None,
    ):
        self._station_map = station_map
        self._matrix = matrix
        self._max_duration = max_duration
        self._max_detour_km = max_detour_km
        self._trip_detour_remaining_m = trip_detour_remaining_m
        self._cargo_weight_capacity_kg = cargo_weight_capacity_kg
        self._cargo_volume_capacity_m3 = cargo_volume_capacity_m3
        self._cargo_weight_capacities = cargo_weight_capacities or {}
        self._cargo_volume_capacities = cargo_volume_capacities or {}
        self._context = context

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
        max_detour_km: float | None = None,
        trip_detour_remaining_m: float | None = None,
        cargo_weight_capacity_kg: float | None = None,
        cargo_volume_capacity_m3: float | None = None,
        context: "FeasibilityContext | None" = None,
    ) -> FeasibilityResult:

        if context is None:
            context = self._context
        if context is not None:
            merged = context.merged_into(self)
            if station_map is None:
                station_map = merged.station_map
            if matrix is None:
                matrix = merged.matrix
            if max_duration is None:
                max_duration = merged.max_duration
            if max_detour_km is None:
                max_detour_km = merged.max_detour_km
            if trip_detour_remaining_m is None:
                trip_detour_remaining_m = merged.trip_detour_remaining_m
            if cargo_weight_capacity_kg is None:
                cargo_weight_capacity_kg = merged.cargo_weight_capacity_kg
            if cargo_volume_capacity_m3 is None:
                cargo_volume_capacity_m3 = merged.cargo_volume_capacity_m3
        if station_map is None:
            station_map = self._station_map
        if matrix is None:
            matrix = self._matrix
        if max_duration is None:
            max_duration = self._max_duration
        if max_detour_km is None:
            max_detour_km = self._max_detour_km
        if trip_detour_remaining_m is None:
            trip_detour_remaining_m = self._trip_detour_remaining_m
        if cargo_weight_capacity_kg is None:
            cargo_weight_capacity_kg = self._cargo_weight_capacity_kg
        if cargo_volume_capacity_m3 is None:
            cargo_volume_capacity_m3 = self._cargo_volume_capacity_m3
        # 按车覆盖优先于全局默认（同 cargo_capacity 的 per-vehicle 口径）
        if cargo_weight_capacity_kg is None and self._cargo_weight_capacities:
            cargo_weight_capacity_kg = self._cargo_weight_capacities.get(route.vehicle_index)
        if cargo_volume_capacity_m3 is None and self._cargo_volume_capacities:
            cargo_volume_capacity_m3 = self._cargo_volume_capacities.get(route.vehicle_index)

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
                cargo_weight_capacity_kg=cargo_weight_capacity_kg,
                cargo_volume_capacity_m3=cargo_volume_capacity_m3,
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

        # Trip / 订单级绕行预算（硬约束，不软化成 penalty）
        if (max_detour_km is not None or trip_detour_remaining_m is not None) and station_map is not None:
            result = self._check_detour_budget(
                route,
                tasks_by_id,
                station_map,
                matrix,
                max_detour_km=max_detour_km,
                trip_detour_remaining_m=trip_detour_remaining_m,
            )
            if not result.feasible:
                return result

        return FeasibilityResult(True)

    @staticmethod
    def _check_detour_budget(
        route: RouteGenome,
        tasks_by_id: dict[str, TaskBlock],
        station_map,
        matrix,
        max_detour_km: float | None,
        trip_detour_remaining_m: float | None,
    ) -> FeasibilityResult:
        """货运绕行预算：max_detour_km 与 trip 剩余预算都是硬约束。"""
        from .evaluator import evaluate_route_genome

        metrics = evaluate_route_genome(
            route, tasks_by_id, station_map, matrix
        )
        detour_m = metrics["cargo_detour"] * 1000.0

        if max_detour_km is not None and metrics["cargo_detour"] > max_detour_km:
            return FeasibilityResult(False, "DETOUR_BUDGET_EXCEEDED")
        if trip_detour_remaining_m is not None and detour_m > trip_detour_remaining_m:
            return FeasibilityResult(False, "TRIP_DETOUR_BUDGET_EXCEEDED")
        return FeasibilityResult(True)

    def validate_solution(
        self,
        routes: list[RouteGenome],
        tasks_by_id: dict[str, TaskBlock],
        passenger_capacities: dict[int, int],
        cargo_capacities: dict[int, int],
        initial_passenger_loads: dict[int, int],
        initial_cargo_loads: dict[int, int],
        station_map=None,
        matrix=None,
        max_duration: float | None = None,
        expected_task_ids: set[str] | None = None,
        max_detour_km: float | None = None,
        trip_detour_remaining_m: float | None = None,
        context: "FeasibilityContext | None" = None,
    ) -> FeasibilityResult:
        """对整个解做最终全面检查（Best RouteGenome 出口统一裁决）。

        覆盖：task uniqueness、task completeness、event legality、
        以及每条 route 的全部硬约束。任何一项失败都返回不可行。
        """
        if station_map is None:
            station_map = self._station_map
        if matrix is None:
            matrix = self._matrix
        if max_duration is None:
            max_duration = self._max_duration
        if context is None:
            context = self._context
        if context is not None:
            merged = context.merged_into(self)
            if station_map is None:
                station_map = merged.station_map
            if matrix is None:
                matrix = merged.matrix
            if max_duration is None:
                max_duration = merged.max_duration
            if max_detour_km is None:
                max_detour_km = merged.max_detour_km
            if trip_detour_remaining_m is None:
                trip_detour_remaining_m = merged.trip_detour_remaining_m
            if expected_task_ids is None:
                expected_task_ids = merged.expected_task_ids
        if max_detour_km is None:
            max_detour_km = self._max_detour_km
        if trip_detour_remaining_m is None:
            trip_detour_remaining_m = self._trip_detour_remaining_m

        seen: set[str] = set()
        for route in routes:
            for task_id in route.placements:
                if task_id in seen:
                    return FeasibilityResult(
                        False,
                        f"TASK_DUPLICATE_ACROSS_ROUTES:{task_id}",
                    )
                seen.add(task_id)

        if expected_task_ids is not None:
            missing = expected_task_ids - seen
            extra = seen - expected_task_ids
            if missing:
                return FeasibilityResult(
                    False,
                    f"TASK_MISSING:{','.join(sorted(missing)[:5])}",
                )
            if extra:
                return FeasibilityResult(
                    False,
                    f"TASK_EXTRA:{','.join(sorted(extra)[:5])}",
                )

        for route in routes:
            ok, reason = route.assert_invariants()
            if not ok:
                return FeasibilityResult(False, f"INVARIANT:{reason}")

            result = self.check(
                route,
                tasks_by_id,
                passenger_capacities[route.vehicle_index],
                cargo_capacities[route.vehicle_index],
                initial_passenger_loads[route.vehicle_index],
                initial_cargo_loads[route.vehicle_index],
                station_map=station_map,
                matrix=matrix,
                max_duration=max_duration,
                max_detour_km=max_detour_km,
                trip_detour_remaining_m=trip_detour_remaining_m,
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
        cargo_weight_capacity_kg: float | None = None,
        cargo_volume_capacity_m3: float | None = None,
    ) -> FeasibilityResult:
        """货物容量检查，口径与 baseline OR-Tools / validators 一致：

        - CargoLoad（车内真实货物）：以 initial_cargo_load 起步；
          SHIPMENT PICKUP +size、DELIVER -size；PRELOADED DELIVERY 与
          standalone PICKUP/DELIVERY 不进 CargoLoad（与 OR-Tools cargo_load_demand 相同）。
        - CargoOut（出程派送累计）：所有 DELIVER（含 SHIPMENT 送达与 PRELOADED）累计 ≤ capacity。
        - CargoIn（返程揽收累计）：所有 PICKUP（含 SHIPMENT 揽收）累计 ≤ capacity。
        - PRELOADED 派送总量 ≤ initial_cargo_load，否则 PRELOAD_INSUFFICIENT。
        - 重量/体积（可选）：车辆配置了 cargoWeight/VolumeCapacity 时，
          车内净重/净体积按 PICKUP+ / DELIVER- 累计，超限即不可行。
        """
        cargo_load = initial_load
        cargo_out = 0
        cargo_in = 0
        preloaded_delivered = 0
        weight_load = 0.0
        volume_load = 0.0

        for event in route.events:

            if not event.task_id:
                continue

            task = tasks_by_id[event.task_id]
            task_weight = float(task.weight_kg or 0.0)
            task_volume = float(task.volume_m3 or 0.0)

            if task.task_type == TaskType.SHIPMENT:

                if event.event_type == EventType.PICKUP:
                    cargo_load += task.size
                    cargo_in += task.size
                    weight_load += task_weight
                    volume_load += task_volume

                elif event.event_type == EventType.DELIVER:
                    cargo_load -= task.size
                    cargo_out += task.size
                    weight_load -= task_weight
                    volume_load -= task_volume

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
                    weight_load += task_weight
                    volume_load += task_volume

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

            if (
                cargo_weight_capacity_kg is not None
                and weight_load > cargo_weight_capacity_kg + 1e-6
            ):
                return FeasibilityResult(
                    False,
                    "CARGO_WEIGHT_CAPACITY_EXCEEDED",
                )

            if (
                cargo_volume_capacity_m3 is not None
                and volume_load > cargo_volume_capacity_m3 + 1e-6
            ):
                return FeasibilityResult(
                    False,
                    "CARGO_VOLUME_CAPACITY_EXCEEDED",
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
