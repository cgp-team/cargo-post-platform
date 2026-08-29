"""Solver 后置验证器：确认方案满足业务约束。

Solver 负责找到方案，Validator 负责确认方案满足业务。
如果 Validator 失败，不返回成功方案。
"""

from __future__ import annotations

from .models import CargoSource, OrderType, PlanOrder, PlanShipment, RouteStop, StopAction, VehiclePlan

# 默认服务时间（秒）
DEFAULT_BOARD_SECONDS = 30
DEFAULT_ALIGHT_SECONDS = 20
DEFAULT_PICKUP_SECONDS = 60
DEFAULT_DELIVERY_SECONDS = 60
DEFAULT_PASS_SECONDS = 0


def service_duration(action: StopAction) -> int:
    """根据 stop action 返回服务时间（秒）。"""
    if action == StopAction.BOARD:
        return DEFAULT_BOARD_SECONDS
    if action == StopAction.ALIGHT:
        return DEFAULT_ALIGHT_SECONDS
    if action == StopAction.PICKUP:
        return DEFAULT_PICKUP_SECONDS
    if action == StopAction.DELIVER:
        return DEFAULT_DELIVERY_SECONDS
    if action == StopAction.PASS:
        return DEFAULT_PASS_SECONDS
    return 0  # DEPART, RETURN


def validate_passenger_load(
    plan: VehiclePlan,
    passenger_capacity: int,
    initial_passenger_load: int = 0,
) -> tuple[bool, str | None]:
    """验证乘客载客约束：BOARD +1, ALIGHT -1，全程峰值 ≤ capacity。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    current = initial_passenger_load
    peak = current

    for stop in plan.stops:
        if stop.action == StopAction.BOARD:
            current += 1
        elif stop.action == StopAction.ALIGHT:
            current -= 1

        if current < 0:
            return False, "PASSENGER_LOAD_NEGATIVE"

        peak = max(peak, current)

    if peak > passenger_capacity:
        return False, "PASSENGER_CAPACITY_EXCEEDED"

    return True, None


def validate_cargo_load(
    plan: VehiclePlan,
    orders_by_id: dict[str, PlanOrder],
    cargo_capacity: int,
    initial_cargo_load: int = 0,
    shipments_by_id: dict[str, PlanShipment] | None = None,
) -> tuple[bool, str | None]:
    """验证车内真实货物量（CargoLoad），与 solver 的 cargo_load_demand 口径一致。

    CargoLoad 只跟踪 PlanShipment（PICKUP +quantity, DELIVERY -quantity）与 initialCargoLoad：
    - PlanShipment：配对货运，揽收装车 +quantity、送达卸车 -quantity
    - PRELOADED DELIVERY：消耗 initialCargoLoad（单独统计 preloaded_delivered 校验不超预装）
    - standalone PICKUP/DELIVERY：不进 CargoLoad，由 CargoOut/CargoIn 维度保证
    - PlanOrder.cargoSource=SHIPMENT：历史死语义（solver 中与 standalone 行为一致），不参与 CargoLoad

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    current = initial_cargo_load
    preloaded_delivered = 0
    shipments_by_id = shipments_by_id or {}

    for stop in plan.stops:
        if not stop.orderId:
            continue

        # PlanShipment：车内真实货物 PICKUP +quantity, DELIVERY -quantity
        shipment = shipments_by_id.get(stop.orderId)
        if shipment is not None:
            if stop.action == StopAction.PICKUP:
                current += shipment.quantity
            elif stop.action == StopAction.DELIVER:
                current -= shipment.quantity
            if current < 0:
                return False, "CARGO_LOAD_NEGATIVE"
            if current > cargo_capacity:
                return False, "CARGO_CAPACITY_EXCEEDED"
            continue

        # PlanOrder：仅 PRELOADED DELIVERY 消耗 initialCargoLoad（其余不进 CargoLoad）
        order = orders_by_id.get(stop.orderId)
        if order is not None and stop.action == StopAction.DELIVER and order.cargoSource == CargoSource.PRELOADED:
            preloaded_delivered += order.itemCount

    # 检查 PRELOADED DELIVERY 是否超过 initialCargoLoad
    if preloaded_delivered > initial_cargo_load:
        return False, "PRELOAD_INSUFFICIENT"

    return True, None


def validate_order_precedence(
    plan: VehiclePlan,
) -> tuple[bool, str | None]:
    """验证订单先后顺序：PICKUP/BOARD 必须在 DELIVER/ALIGHT 之前。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    seen: dict[str, dict[str, int]] = {}  # order_id -> {action: position}

    for i, stop in enumerate(plan.stops):
        if not stop.orderId:
            continue

        if stop.orderId not in seen:
            seen[stop.orderId] = {}
        seen[stop.orderId][stop.action.value] = i

    for order_id, actions in seen.items():
        # 客运：BOARD 必须在 ALIGHT 之前
        if "BOARD" in actions and "ALIGHT" in actions:
            if actions["BOARD"] >= actions["ALIGHT"]:
                return False, "BOARD_AFTER_ALIGHT"

        # 货运配对（PlanShipment：同一 shipmentId 有 PICKUP + DELIVER）：PICKUP 必须在 DELIVER 之前
        # 注意：单向揽收（只有 PICKUP）或单向派送（只有 DELIVER）是合法的
        if "PICKUP" in actions and "DELIVER" in actions:
            if actions["PICKUP"] >= actions["DELIVER"]:
                return False, "PICKUP_AFTER_DELIVER"

    return True, None


def validate_skeleton(
    plan: VehiclePlan,
    skeleton_stations: list[str],
) -> tuple[bool, str | None]:
    """验证骨架顺序：skeleton 站点必须按序出现在 PASS 停靠中。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    if not skeleton_stations:
        return True, None

    pass_stations = [
        stop.stationId for stop in plan.stops if stop.action == StopAction.PASS
    ]

    # 检查骨架站点是否按序出现
    skel_idx = 0
    for station in pass_stations:
        if skel_idx < len(skeleton_stations) and station == skeleton_stations[skel_idx]:
            skel_idx += 1

    if skel_idx < len(skeleton_stations):
        return False, "SKELETON_ORDER_VIOLATION"

    return True, None


def validate_road_segments(
    plan: VehiclePlan,
) -> tuple[bool, str | None]:
    """验证路段完整性：segmentDistance 不能为负，segmentDuration 不能为负。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    for i, stop in enumerate(plan.stops):
        if stop.segmentDistance < 0:
            return False, "NEGATIVE_SEGMENT_DISTANCE"
        if stop.segmentDuration is not None and stop.segmentDuration < 0:
            return False, "NEGATIVE_SEGMENT_DURATION"
    return True, None


def validate_vehicle_plan(
    plan: VehiclePlan,
    vehicle: any,  # Vehicle model
    orders_by_id: dict[str, PlanOrder],
    shipments_by_id: dict[str, PlanShipment] | None = None,
) -> tuple[bool, str | None]:
    """综合验证一辆车的方案。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    # 1. 乘客载荷
    valid, reason = validate_passenger_load(
        plan, vehicle.passengerCapacity, vehicle.initialPassengerLoad
    )
    if not valid:
        return False, reason

    # 2. 货物载荷
    valid, reason = validate_cargo_load(
        plan, orders_by_id, vehicle.cargoCapacity, vehicle.initialCargoLoad, shipments_by_id
    )
    if not valid:
        return False, reason

    # 3. 订单先后顺序
    valid, reason = validate_order_precedence(plan)
    if not valid:
        return False, reason

    # 4. 骨架顺序
    if vehicle.skeleton:
        valid, reason = validate_skeleton(plan, vehicle.skeleton)
        if not valid:
            return False, reason

    # 5. 路段完整性
    valid, reason = validate_road_segments(plan)
    if not valid:
        return False, reason

    return True, None


def validate_time_window(
    plan: VehiclePlan,
    batch_start_seconds: int,
    batch_end_seconds: int,
) -> tuple[bool, str | None]:
    """验证时间窗口约束：任务必须在 batchStart ~ batchEnd 内完成。

    计算方式：
    current_time = batchStart
    for stop in stops:
        current_time += segmentDuration (行驶时间)
        current_time += serviceDuration (服务时间)
    if current_time > batchEnd: EXCEEDED

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    current_time = batch_start_seconds

    for i, stop in enumerate(plan.stops):
        # 行驶时间（第一段 DEPART 无行驶时间）
        if i > 0 and stop.segmentDuration is not None:
            current_time += int(stop.segmentDuration)

        # 服务时间
        current_time += service_duration(stop.action)

    if current_time > batch_end_seconds:
        return False, "TIME_WINDOW_EXCEEDED"

    return True, None
