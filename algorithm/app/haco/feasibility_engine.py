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
    # 逐单时效的时钟原点（= request.batchStart 的 epoch 秒）。
    # None = 该批次没有任何时效字段 → 完全不检查（旧行为，不凭空造承诺时刻）。
    base_time_s: float | None = None

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
            base_time_s=(
                self.base_time_s if self.base_time_s is not None else engine._base_time_s
            ),
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
        # 逐单时效原点（request.batchStart epoch 秒）；None = 本批次不检查逐单时效
        base_time_s: float | None = None,
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
        self._base_time_s = base_time_s
        self._context = context

    @property
    def base_time(self) -> float | None:
        """时效时钟原点（epoch 秒）；None = 本批次不核逐单时效。

        构造阶段的候选剪枝需要它，但不该伸手碰私有属性 —— 剪枝口径必须和
        `_check_timing` 的时效档用同一个原点，否则一边认为可行、另一边判迟到。
        """
        return self._base_time_s

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
        base_time_s: float | None = None,
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
        if base_time_s is None:
            base_time_s = (
                merged.base_time_s if context is not None else self._base_time_s
            )
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
            (max_duration is not None or base_time_s is not None)
            and station_map is not None
        ):
            # 一次复核同时给出「批级总耗时」与「逐单时效」两个判据（口径与顺序见 _check_timing）
            result = self._check_timing(
                route,
                tasks_by_id,
                station_map,
                matrix,
                max_duration,
                base_time_s,
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
        # 出程派送件累计重量/体积（发车即在舱内，逐站卸货）：与 CargoOut 件数口径同构
        out_weight_load = 0.0
        out_volume_load = 0.0

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

                    # 派送件是发车时就在舱内的货：件数口径靠 CargoOut 累计上限管，
                    # 重量/体积也必须同样管起来，否则「一件 50kg 的大件派送」对载重不可见。
                    # PRELOADED 例外：它由 initialCargoLoad 承载，而契约没有
                    # initialCargoWeightKg，按 0 计是保守做法（不凭空造上限）。
                    if task.cargo_source != CargoSource.PRELOADED:
                        out_weight_load += task_weight
                        out_volume_load += task_volume

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

        # 出程派送件累计重量/体积与件数同构：派送件发车即在舱内，
        # 只按件数设上限会让「50kg 一件的大件」对载重完全不可见。
        if (
            cargo_weight_capacity_kg is not None
            and out_weight_load > cargo_weight_capacity_kg + 1e-6
        ):
            return FeasibilityResult(
                False,
                "CARGO_OUT_WEIGHT_EXCEEDED",
            )

        if (
            cargo_volume_capacity_m3 is not None
            and out_volume_load > cargo_volume_capacity_m3 + 1e-6
        ):
            return FeasibilityResult(
                False,
                "CARGO_OUT_VOLUME_EXCEEDED",
            )

        if preloaded_delivered > initial_load:
            return FeasibilityResult(
                False,
                "PRELOAD_INSUFFICIENT",
            )

        return FeasibilityResult(True)

    @staticmethod
    def _check_timing(
        route,
        tasks_by_id,
        station_map,
        matrix,
        max_duration,
        base_time_s,
    ):
        """整路线时间复核：批级总耗时（不含等待）+ 逐单时效（含等待）。

        原来这是两个检查（`_check_duration` 与 `_check_timeliness`），各自从头走一遍
        `route.events`，于是每段 OD 的 `compute_duration` 被算了两遍 —— §15.1 的剖面
        量到的热点正在这条"每次评估都要跑的整路线复核"上（任务 #10）。

        三档分开走，是因为**第一版把腿时长预先存进列表**，结果在"早早违规"的那一档反而更贵
        （合并前一支检查走完整趟、另一支到第一个违规就返回；预存列表等于放弃那次提前返回）。
        实测分档微观对照（`C:\\temp\\bench_timing.py`，31 事件路线、同一矩阵、20k 次）：
        两档都开且全程可行 20.8µs → 13.9µs（−33%，每段腿查 60 → 30 次）、长路线 38.4 → 24.2µs；
        只开逐单一档 12.5 → 11.8µs；只开批级一档 8.2 → 8.6µs（这一档每个事件多一次比较，
        换来"中途就定案"，可行路线那侧略亏 —— 生产口径两档都开，所以净赚）。

        口径与判据顺序一位都没动：
        ① `max_duration` 按「不含等待」判（各段行驶 + 每站服务）；总量单调递增，所以中途超过
           就能定案，且它**压过**任何已经发生的迟到（合并前 ① 是整路线先判完才轮到时效）；
        ② 含等待的时钟逐单判 `readyTime` / `pickupDeadline` / `deliveryDeadline`
           （**货没打包好之前车取不到件**：早到必须等到 readyTime，等待会推后该单自己的送达
           以及它之后所有事件的时刻；截止比的是完成时刻 = 到达 + 服务时间），记第一个违规位；
        ③ 都没超再按含等待的收钟复核批级上限 —— 忽略等待会让超时方案看起来合规
           （DISPATCH_CORE_V047 §15）。
        """
        from .heuristic import compute_duration

        events = route.events
        eps = 1e-6

        # 只有批级一档：合并前 `_check_duration` 的口径，一条流式累加循环。
        if base_time_s is None:
            if max_duration is None:
                return FeasibilityResult(True)
            total = 0.0
            for i, event in enumerate(events):
                if i > 0:
                    total += compute_duration(
                        station_map[events[i - 1].station_id],
                        station_map[event.station_id],
                        matrix,
                    )
                total += service_duration(
                    _EVENT_TO_ACTION.get(event.event_type, StopAction.PASS)
                )
                if total > max_duration:
                    return FeasibilityResult(False, "TIME_WINDOW_EXCEEDED")
            return FeasibilityResult(True)

        clock = float(base_time_s)

        # 只有逐单时效一档：合并前 `_check_timeliness` 的口径，第一个违规立刻返回。
        if max_duration is None:
            for i, event in enumerate(events):
                if i > 0:
                    clock += compute_duration(
                        station_map[events[i - 1].station_id],
                        station_map[event.station_id],
                        matrix,
                    )
                task = tasks_by_id.get(event.task_id) if event.task_id else None
                if task is not None and event.event_type == EventType.PICKUP:
                    if task.ready_time_s is not None and clock < task.ready_time_s:
                        clock = float(task.ready_time_s)
                clock += service_duration(
                    _EVENT_TO_ACTION.get(event.event_type, StopAction.PASS)
                )
                if task is None:
                    continue
                if (event.event_type == EventType.PICKUP
                        and task.pickup_deadline_s is not None
                        and clock > task.pickup_deadline_s + eps):
                    return FeasibilityResult(False, "ETA_MISSED_PICKUP")
                if (event.event_type == EventType.DELIVER
                        and task.delivery_deadline_s is not None
                        and clock > task.delivery_deadline_s + eps):
                    return FeasibilityResult(False, "ETA_MISSED_DELIVERY")
            return FeasibilityResult(True)

        # 两档都要：一趟同时养两个累加器（合并前这段要走两趟、每段腿算两次）
        total = 0.0
        first_eta: str | None = None
        for i, event in enumerate(events):
            leg = 0.0
            if i > 0:
                leg = compute_duration(
                    station_map[events[i - 1].station_id],
                    station_map[event.station_id],
                    matrix,
                )
            svc = service_duration(
                _EVENT_TO_ACTION.get(event.event_type, StopAction.PASS)
            )
            total += leg + svc
            if total > max_duration:
                return FeasibilityResult(False, "TIME_WINDOW_EXCEEDED")
            if first_eta is not None:
                continue          # 迟到已经定了，剩下只为"批级会不会先压它一头"而继续走
            clock += leg
            task = tasks_by_id.get(event.task_id) if event.task_id else None
            if task is not None and event.event_type == EventType.PICKUP:
                if task.ready_time_s is not None and clock < task.ready_time_s:
                    clock = float(task.ready_time_s)
            clock += svc
            if task is None:
                continue
            if (event.event_type == EventType.PICKUP
                    and task.pickup_deadline_s is not None
                    and clock > task.pickup_deadline_s + eps):
                first_eta = "ETA_MISSED_PICKUP"
            elif (event.event_type == EventType.DELIVER
                    and task.delivery_deadline_s is not None
                    and clock > task.delivery_deadline_s + eps):
                first_eta = "ETA_MISSED_DELIVERY"

        if first_eta is not None:
            return FeasibilityResult(False, first_eta)
        if clock > base_time_s + max_duration + eps:
            return FeasibilityResult(False, "TIME_WINDOW_EXCEEDED")
        return FeasibilityResult(True)
