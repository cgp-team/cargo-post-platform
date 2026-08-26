"""OR-Tools 路线规划求解器（pywrapcp 路由模型）。

模型要点：
- 节点：0 为场站；每个客运订单拆为上车站节点 + 下车站节点（强制同车、先上后下），
  派送/揽收订单各占一个作业站点节点。
- 载客维度：BOARD +1, ALIGHT -1，座位动态释放。CumulVar 语义为到达节点时的
  累计值（transit 之前），下界=0 阻止负 cumul。允许重访站点分批上下客。
- 载货双维度：CargoOut（DELIVER +itemCount）/ CargoIn（PICKUP +itemCount）
  独立累计，出程派送、返程揽收，货仓依次复用。
- 优先单车（契约 Q7）：每台启用车辆计大额固定成本，目标函数先最小化用车数、再最小化总里程。
- 距离：默认 GCJ-02 坐标两点欧氏直线（单位：度）；调用方注入距离矩阵（如高德路网，
  单位：公里）时按矩阵查表。距离乘以 DISTANCE_SCALE 取整后作为求解成本，输出里程
  换算回原单位，保留 3 位小数，与 mock 口径一致。
- 确定性：仅用确定性的首解构造策略（PATH_CHEAPEST_ARC），不引入任何
  随机元启发式或 wall-clock 依赖，同输入必然同输出。
"""

from dataclasses import dataclass, field
from math import hypot

from ortools.constraint_solver import pywrapcp, routing_enums_pb2

from .distance import EUCLIDEAN_AVG_SPEED_KMH, DistanceMatrix, haversine_km
from .models import CargoSource, OrderType, PlanRequest, RouteStop, StopAction, VehiclePlan
from .validators import validate_time_window, validate_vehicle_plan

# 距离（度或公里，随距离提供方）× 1000 取整作为整数成本，对应输出保留 3 位小数
DISTANCE_SCALE = 1000
# 求解时间护栏：本规模首解构造远低于 1 秒，10 秒契约时限内留足余量
SOLVER_TIME_LIMIT_SECONDS = 5


@dataclass
class SolveOutcome:
    status: str  # "feasible" | "infeasible"
    reason_code: str | None = None
    vehicle_plans: list[VehiclePlan] = field(default_factory=list)
    total_distance: float = 0.0


@dataclass
class _Node:
    station_id: str
    action: StopAction
    order_id: str | None = None  # 骨架 PASS 节点无订单


def _scaled_distance(a, b) -> int:
    return int(round(hypot(a.longitude - b.longitude, a.latitude - b.latitude) * DISTANCE_SCALE))


def _total_demand(request: PlanRequest) -> tuple[int, int, int]:
    """总需求统计：载客人数 / 派送件总量 / 揽收件总量。

    载货按「净载荷·出程/返程」口径拆分：派送件在出程占货仓（到站点卸载）、
    揽收件在返程占货仓（从站点装载），两向不互相挤占，故分别与总货仓容量比较，
    而非旧的「派送+揽收 全部累计 ≤ 容量」（旧口径把返程可用仓位置 0，闲置运力无法利用）。

    注意：PRELOADED DELIVERY 不计入 deliveries（已通过 initialCargoLoad 处理）。
    """
    passengers = sum(1 for order in request.orders if order.orderType == OrderType.PASSENGER)
    # 排除明确标记为 PRELOADED 的 DELIVERY（已通过 initialCargoLoad 处理）
    # cargoSource=None 的 DELIVERY 按旧逻辑处理（计入 deliveries）
    deliveries = sum(
        order.itemCount for order in request.orders
        if order.orderType == OrderType.DELIVERY
        and order.cargoSource != CargoSource.PRELOADED
    )
    pickups = sum(order.itemCount for order in request.orders if order.orderType == OrderType.PICKUP)
    # PlanShipment 同时贡献派送和揽收
    shipment_quantity = sum(s.quantity for s in request.shipments)
    deliveries += shipment_quantity
    pickups += shipment_quantity
    return passengers, deliveries, pickups


def solve(request: PlanRequest, matrix: DistanceMatrix | None = None) -> SolveOutcome:
    """求解一个批次的路线规划。契约口径：总需求超总容量 → OVER_CAPACITY；

    其余无解（时序矛盾等）→ TIMING_CONFLICT；不返回部分方案。
    matrix 为 None 时用两点欧氏直线（度）；注入矩阵（如高德路网，公里）时按站点对查表，
    成本缩放与输出换算逻辑不变，segmentDistance/totalDistance 跟随矩阵单位。
    """
    passengers, deliveries, pickups = _total_demand(request)
    if not request.orders and not request.shipments:
        return SolveOutcome(status="feasible")
    # 初始载荷校验：initialPassengerLoad 必须 < passengerCapacity
    # initialCargoLoad 可以 = cargoCapacity（车辆满载但可以派送）
    for v in request.vehicles:
        if v.initialPassengerLoad >= v.passengerCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")
        if v.initialCargoLoad > v.cargoCapacity:
            return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")
    total_passenger_capacity = sum(v.passengerCapacity - v.initialPassengerLoad for v in request.vehicles)
    total_cargo_capacity = sum(v.cargoCapacity - v.initialCargoLoad for v in request.vehicles)
    if passengers > total_passenger_capacity or deliveries > total_cargo_capacity or pickups > total_cargo_capacity:
        return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")

    # PRELOADED DELIVERY 校验：仅当 cargoSource 明确为 PRELOADED 时检查
    # cargoSource=None 的 DELIVERY 按旧逻辑处理（不强制要求 initialCargoLoad）
    total_preloaded_delivery = sum(
        order.itemCount for order in request.orders
        if order.orderType == OrderType.DELIVERY
        and order.cargoSource == CargoSource.PRELOADED
    )
    total_initial_cargo = sum(v.initialCargoLoad for v in request.vehicles)
    if total_preloaded_delivery > total_initial_cargo:
        return SolveOutcome(status="infeasible", reason_code="PRELOAD_INSUFFICIENT")

    station_map = {station.stationId: station for station in request.stations}
    station_map[request.depot.stationId] = request.depot

    # 矩阵完整性预检：所有站点对必须在矩阵中（防止 pywrapcp 崩溃）
    if matrix is not None:
        all_station_ids = list(station_map.keys())
        missing = [
            (a, b)
            for a in all_station_ids
            for b in all_station_ids
            if (a, b) not in matrix
        ]
        if missing:
            return SolveOutcome(status="infeasible", reason_code="DISTANCE_MATRIX_INCOMPLETE")

    # 节点 0 为场站，其后按订单展开作业节点
    nodes: list[_Node] = [_Node(station_id=request.depot.stationId, action=StopAction.DEPART)]
    passenger_pairs: list[tuple[int, int]] = []  # (board_node, alight_node)
    shipment_pairs: list[tuple[int, int, str]] = []  # (pickup_node, delivery_node, shipmentId)
    for order in request.orders:
        if order.orderType == OrderType.PASSENGER:
            board_node = len(nodes)
            nodes.append(_Node(order.boardingStationId, StopAction.BOARD, order.orderId))
            nodes.append(_Node(order.alightingStationId, StopAction.ALIGHT, order.orderId))
            passenger_pairs.append((board_node, board_node + 1))
        elif order.orderType == OrderType.DELIVERY:
            nodes.append(_Node(order.stationId, StopAction.DELIVER, order.orderId))
        else:
            nodes.append(_Node(order.stationId, StopAction.PICKUP, order.orderId))

    # PlanShipment 展开为 PICKUP + DELIVERY 配对
    for shipment in request.shipments:
        pickup_node = len(nodes)
        nodes.append(_Node(shipment.pickupStationId, StopAction.PICKUP, shipment.shipmentId))
        nodes.append(_Node(shipment.deliveryStationId, StopAction.DELIVER, shipment.shipmentId))
        shipment_pairs.append((pickup_node, pickup_node + 1, shipment.shipmentId))

    # 公交骨架节点（Mandatory Passenger Service）：提供 skeleton 的车辆必须按顺序经停。
    # 骨架站点作为 PASS 经停，货运/揽收作为绕行插入骨架间隙。
    skeleton_nodes: dict[int, list[int]] = {}  # vehicle_index -> [node_idx, ...]
    skeleton_sets: dict[int, set[str]] = {}  # vehicle_index -> set(stationId)（绕行判定用）
    for vehicle_index, vehicle in enumerate(request.vehicles):
        if not vehicle.skeleton:
            continue
        skel_stations = [sid for sid in vehicle.skeleton if sid in station_map]
        if not skel_stations:
            continue
        idxs: list[int] = []
        for sid in skel_stations:
            nodes.append(_Node(sid, StopAction.PASS))
            idxs.append(len(nodes) - 1)
        skeleton_nodes[vehicle_index] = idxs
        skeleton_sets[vehicle_index] = set(skel_stations)

    num_vehicles = len(request.vehicles)
    manager = pywrapcp.RoutingIndexManager(len(nodes), num_vehicles, 0)
    routing = pywrapcp.RoutingModel(manager)

    def segment_info(from_station, to_station) -> tuple[float, float, str]:
        """统一分段信息：(distance_km, duration_seconds, provider)。

        AMAP：使用 DistanceMatrix（km, seconds）
        Euclidean：使用 haversine_km + EUCLIDEAN_AVG_SPEED_KMH 估算
        """
        if matrix is not None:
            km, seconds = matrix[(from_station.stationId, to_station.stationId)]
            return km, seconds, "amap"
        km = haversine_km(from_station.longitude, from_station.latitude,
                          to_station.longitude, to_station.latitude)
        seconds = round(km / EUCLIDEAN_AVG_SPEED_KMH * 3600) if km > 0 else 0.0
        return km, seconds, "euclidean"

    def scaled_distance(from_station, to_station) -> int:
        if matrix is None:
            return _scaled_distance(from_station, to_station)
        return int(round(matrix[(from_station.stationId, to_station.stationId)][0] * DISTANCE_SCALE))

    def segment_seconds(from_station, to_station) -> float:
        """分段行驶秒数：AMAP 用真实秒数，欧氏用 Haversine 均速估算。"""
        _, seconds, _ = segment_info(from_station, to_station)
        return seconds

    def distance_callback(from_index: int, to_index: int) -> int:
        from_node = manager.IndexToNode(from_index)
        to_node = manager.IndexToNode(to_index)
        return scaled_distance(station_map[nodes[from_node].station_id], station_map[nodes[to_node].station_id])

    distance_callback_index = routing.RegisterTransitCallback(distance_callback)
    routing.SetArcCostEvaluatorOfAllVehicles(distance_callback_index)

    # 动态计算车辆固定成本：确保"多用一辆车"的成本 > "任何额外距离"的成本
    # 上界 = 节点数 × 最大单段距离 × DISTANCE_SCALE + 1
    max_pair_dist = 0
    for a in station_map.values():
        for b in station_map.values():
            d = scaled_distance(a, b)
            if d > max_pair_dist:
                max_pair_dist = d
    vehicle_fixed_cost = len(nodes) * max_pair_dist + 1 if max_pair_dist > 0 else 10**9

    for vehicle_index in range(num_vehicles):
        routing.SetFixedCostOfVehicle(vehicle_fixed_cost, vehicle_index)

    # 载客维度：BOARD +1, ALIGHT -1，座位动态释放。
    # CumulVar 语义：到达节点时的累计值（transit 之前），下界=0 阻止负 cumul。
    # fix_start_cumul_to_0=False：支持 initialPassengerLoad（车辆出发时已有乘客）。
    def passenger_demand(node: int) -> int:
        action = nodes[node].action
        if action == StopAction.BOARD:
            return 1
        if action == StopAction.ALIGHT:
            return -1
        return 0

    passenger_callback_index = routing.RegisterUnaryTransitCallback(
        lambda from_index: passenger_demand(manager.IndexToNode(from_index))
    )
    routing.AddDimensionWithVehicleCapacity(
        passenger_callback_index,
        0,
        [v.passengerCapacity for v in request.vehicles],
        False,
        "Passenger",
    )
    passenger_dimension = routing.GetDimensionOrDie("Passenger")
    for vehicle_index, vehicle in enumerate(request.vehicles):
        start_index = routing.Start(vehicle_index)
        initial_load = vehicle.initialPassengerLoad
        passenger_dimension.CumulVar(start_index).SetRange(initial_load, initial_load)

    # 载货双维度（净载荷·出程/返程）：
    #   - CargoOut 派送维度：DELIVER +itemCount，单车次累计派送件 ≤ cargoCapacity
    #     （出程时货仓满装派送件，到站逐件卸载释放仓位）；
    #   - CargoIn 揽收维度：PICKUP +itemCount，单车次累计揽收件 ≤ cargoCapacity
    #     （返程时装载揽收件，回到场站统一卸载）。
    # 两向独立累计，正是「公交闲置运力」运营模型：出程派送、返程揽收，货仓依次复用，
    # 不再像旧口径那样把「派送+揽收」一起累计导致返程仓位被置 0、闲置运力无法利用。
    item_count = {
        order.orderId: order.itemCount for order in request.orders if order.orderType != OrderType.PASSENGER
    }
    # PlanShipment 的 quantity 同时用于 PICKUP 和 DELIVERY
    for shipment in request.shipments:
        item_count[shipment.shipmentId] = shipment.quantity

    def delivery_demand(node: int) -> int:
        if nodes[node].action == StopAction.DELIVER:
            return item_count[nodes[node].order_id]
        return 0

    def pickup_demand(node: int) -> int:
        if nodes[node].action == StopAction.PICKUP:
            return item_count[nodes[node].order_id]
        return 0

    delivery_callback_index = routing.RegisterUnaryTransitCallback(
        lambda from_index: delivery_demand(manager.IndexToNode(from_index))
    )
    routing.AddDimensionWithVehicleCapacity(
        delivery_callback_index,
        0,
        [v.cargoCapacity for v in request.vehicles],
        True,
        "CargoOut",
    )
    pickup_callback_index = routing.RegisterUnaryTransitCallback(
        lambda from_index: pickup_demand(manager.IndexToNode(from_index))
    )
    routing.AddDimensionWithVehicleCapacity(
        pickup_callback_index,
        0,
        [v.cargoCapacity for v in request.vehicles],
        True,
        "CargoIn",
    )

    # CurrentCargoLoad 维度：车上真实货物量
    # 与 CargoOut/CargoIn 的区别：
    #   - CargoOut/CargoIn：业务方向维度（出程派送能力/返程揽收能力），各自独立累计
    #   - CurrentCargoLoad：真实车内状态，支持 initialCargoLoad
    #
    # CargoSource 区分：
    #   - PRELOADED DELIVERY：消耗 initialCargoLoad，不产生 demand（已在 start cumul 中）
    #   - SHIPMENT PICKUP/DELIVERY：PICKUP +quantity, DELIVERY -quantity
    #   - standalone PICKUP：不进入 CargoLoad（由 CargoIn 维度保证）
    shipment_ids = {s.shipmentId for s in request.shipments}
    # 构建 order 的 cargoSource 映射
    order_cargo_source: dict[str, CargoSource | None] = {}
    for order in request.orders:
        if order.orderType in (OrderType.DELIVERY, OrderType.PICKUP):
            order_cargo_source[order.orderId] = order.cargoSource

    def cargo_load_demand(node: int) -> int:
        action = nodes[node].action
        order_id = nodes[node].order_id
        if not order_id:
            return 0

        # Shipment 配对：PICKUP +quantity, DELIVERY -quantity
        if order_id in shipment_ids:
            if action == StopAction.PICKUP:
                return item_count.get(order_id, 0)
            if action == StopAction.DELIVER:
                return -item_count.get(order_id, 0)
            return 0

        # PRELOADED DELIVERY：不产生 demand（消耗 initialCargoLoad）
        source = order_cargo_source.get(order_id)
        if action == StopAction.DELIVER and source == CargoSource.PRELOADED:
            return 0  # 已在 initialCargoLoad 中处理

        # standalone PICKUP：不进入 CargoLoad（由 CargoIn 维度保证）
        if action == StopAction.PICKUP and source != CargoSource.SHIPMENT:
            return 0

        return 0

    cargo_load_callback_index = routing.RegisterUnaryTransitCallback(
        lambda from_index: cargo_load_demand(manager.IndexToNode(from_index))
    )
    routing.AddDimensionWithVehicleCapacity(
        cargo_load_callback_index,
        0,
        [v.cargoCapacity for v in request.vehicles],
        False,
        "CargoLoad",
    )
    cargo_load_dimension = routing.GetDimensionOrDie("CargoLoad")
    for vehicle_index, vehicle in enumerate(request.vehicles):
        start_index = routing.Start(vehicle_index)
        initial_cargo = vehicle.initialCargoLoad
        cargo_load_dimension.CumulVar(start_index).SetRange(initial_cargo, initial_cargo)

    # 里程维度：供客运先上后下的时序约束使用（距离累计单调不减）
    routing.AddDimension(distance_callback_index, 0, 10**12, True, "Distance")
    distance_dimension = routing.GetDimensionOrDie("Distance")

    # 客运订单：强制同车 + 先上车后下车
    solver = routing.solver()
    for board_node, alight_node in passenger_pairs:
        board_index = manager.NodeToIndex(board_node)
        alight_index = manager.NodeToIndex(alight_node)
        routing.AddPickupAndDelivery(board_index, alight_index)
        solver.Add(routing.VehicleVar(board_index) == routing.VehicleVar(alight_index))
        solver.Add(distance_dimension.CumulVar(board_index) <= distance_dimension.CumulVar(alight_index))

    # Shipment 配对约束：同一 shipment 的 PICKUP 和 DELIVERY 必须同车、PICKUP 在 DELIVERY 前
    for pickup_node, delivery_node, shipment_id in shipment_pairs:
        pickup_index = manager.NodeToIndex(pickup_node)
        delivery_index = manager.NodeToIndex(delivery_node)
        routing.AddPickupAndDelivery(pickup_index, delivery_index)
        solver.Add(routing.VehicleVar(pickup_index) == routing.VehicleVar(delivery_index))
        solver.Add(distance_dimension.CumulVar(pickup_index) <= distance_dimension.CumulVar(delivery_index))

    # 公交骨架约束：骨架站点必须由指定车辆按顺序经停（Mandatory Passenger Service，不可删站/跳站）
    for vehicle_index, skel in skeleton_nodes.items():
        for skel_node in skel:
            solver.Add(routing.VehicleVar(manager.NodeToIndex(skel_node)) == vehicle_index)
        for i in range(len(skel) - 1):
            solver.Add(distance_dimension.CumulVar(manager.NodeToIndex(skel[i]))
                       <= distance_dimension.CumulVar(manager.NodeToIndex(skel[i + 1])))

    search_parameters = pywrapcp.DefaultRoutingSearchParameters()
    # PATH_CHEAPEST_ARC（确定性）：对公交骨架的「按序经停」cumul 顺序约束更稳健；
    # PARALLEL_CHEAPEST_INSERTION 在骨架顺序约束下首解构造会失败。
    search_parameters.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    search_parameters.time_limit.FromSeconds(SOLVER_TIME_LIMIT_SECONDS)

    solution = routing.SolveWithParameters(search_parameters)
    if solution is None:
        return SolveOutcome(status="infeasible", reason_code="TIMING_CONFLICT")

    vehicle_plans: list[VehiclePlan] = []
    for vehicle_index, vehicle in enumerate(request.vehicles):
        index = routing.Start(vehicle_index)
        if routing.IsEnd(solution.Value(routing.NextVar(index))):
            continue  # 未启用的车辆不输出方案
        stops = [RouteStop(stationId=request.depot.stationId, action=StopAction.DEPART)]
        scaled_total = 0

        # 先收集完整路线节点序列，用于计算真实绕行距离
        route_indices = [index]
        while True:
            next_index = solution.Value(routing.NextVar(index))
            route_indices.append(next_index)
            if routing.IsEnd(next_index):
                break
            index = next_index

        # 构建 stops 并计算绕行
        for step in range(1, len(route_indices)):
            prev_index = route_indices[step - 1]
            curr_index = route_indices[step]

            from_station = station_map[nodes[manager.IndexToNode(prev_index)].station_id]
            if routing.IsEnd(curr_index):
                to_station = request.depot
            else:
                to_station = station_map[nodes[manager.IndexToNode(curr_index)].station_id]

            segment = scaled_distance(from_station, to_station)
            scaled_total += segment
            segment_km = segment / DISTANCE_SCALE
            seg_sec = segment_seconds(from_station, to_station)

            if routing.IsEnd(curr_index):
                stops.append(RouteStop(
                    stationId=request.depot.stationId,
                    action=StopAction.RETURN,
                    segmentDistance=segment_km,
                    segmentDuration=seg_sec,
                ))
                break

            node = nodes[manager.IndexToNode(curr_index)]
            route_stop: dict = {
                "stationId": node.station_id,
                "orderId": node.order_id,
                "action": node.action,
                "segmentDistance": segment_km,
                "segmentDuration": seg_sec,
            }

            # 算法解释（仅货运/揽收经停）
            if node.order_id and node.action in (StopAction.PICKUP, StopAction.DELIVER):
                on_skeleton = node.station_id in skeleton_sets.get(vehicle_index, set())

                # 计算真实绕行距离：prev→cargo→next vs prev→next
                detour_distance = 0.0
                detour_duration = 0.0
                if not on_skeleton and step + 1 < len(route_indices):
                    next_stop_index = route_indices[step + 1]
                    if routing.IsEnd(next_stop_index):
                        next_station = request.depot
                    else:
                        next_station = station_map[nodes[manager.IndexToNode(next_stop_index)].station_id]

                    direct_dist = scaled_distance(from_station, next_station) / DISTANCE_SCALE
                    via_dist = segment_km + scaled_distance(to_station, next_station) / DISTANCE_SCALE
                    detour_distance = max(0.0, via_dist - direct_dist)

                    direct_sec = segment_seconds(from_station, next_station)
                    via_sec = seg_sec + segment_seconds(to_station, next_station)
                    detour_duration = max(0.0, via_sec - direct_sec)

                # Passenger Impact = detour duration（route-level，非 passenger-level）
                passenger_impact = detour_duration if detour_duration > 0 else None

                # 绕行阈值检查
                config = request.algorithmConfig
                accepted = True
                service_mode = "NEAREST_STATION"
                reason_code = None

                if not on_skeleton:
                    # 检查距离阈值
                    if config.maxDetourDistanceKm is not None and detour_distance > config.maxDetourDistanceKm:
                        accepted = False
                        reason_code = "DETOUR_DISTANCE_EXCEEDED"
                        service_mode = "NEAREST_STATION"

                    # 检查时间阈值
                    if config.maxDetourDurationSeconds is not None and detour_duration > config.maxDetourDurationSeconds:
                        accepted = False
                        reason_code = "DETOUR_DURATION_EXCEEDED"
                        service_mode = "NEAREST_STATION"

                    # 检查乘客影响阈值
                    if config.maxPassengerImpactSeconds is not None and detour_duration > config.maxPassengerImpactSeconds:
                        accepted = False
                        reason_code = "PASSENGER_IMPACT_EXCEEDED"
                        service_mode = "NEAREST_STATION"

                route_stop.update(
                    accepted=accepted,
                    serviceMode=service_mode,
                    servicePoint=node.station_id,
                    detourDistance=detour_distance,
                    detourDuration=detour_duration,
                    passengerImpact=passenger_impact,
                    reasonCode=reason_code,
                )

            stops.append(RouteStop(**route_stop))
        vehicle_plans.append(
            VehiclePlan(
                vehicleId=vehicle.vehicleId,
                stops=stops,
                totalDistance=scaled_total / DISTANCE_SCALE,
            )
        )

    # 后置验证：按 vehicleId 映射（vehicle_plans 只含启用车辆，不能 zip 对齐）
    orders_by_id = {order.orderId: order for order in request.orders}
    vehicles_by_id = {vehicle.vehicleId: vehicle for vehicle in request.vehicles}
    for plan in vehicle_plans:
        vehicle = vehicles_by_id.get(plan.vehicleId)
        if vehicle is None:
            return SolveOutcome(status="infeasible", reason_code="VEHICLE_NOT_FOUND")
        valid, reason = validate_vehicle_plan(plan, vehicle, orders_by_id)
        if not valid:
            return SolveOutcome(status="infeasible", reason_code=reason)

    # 时间窗口验证（所有路径都执行，segmentDuration=None 时视为不可用）
    batch_start_s = int(request.batchStart.timestamp())
    batch_end_s = int(request.batchEnd.timestamp())
    for plan in vehicle_plans:
        has_missing_duration = any(
            stop.segmentDuration is None for stop in plan.stops[1:]
        )
        if has_missing_duration:
            return SolveOutcome(status="infeasible", reason_code="ROUTE_DURATION_UNAVAILABLE")
        valid, reason = validate_time_window(plan, batch_start_s, batch_end_s)
        if not valid:
            return SolveOutcome(status="infeasible", reason_code=reason)

    return SolveOutcome(
        status="feasible",
        vehicle_plans=vehicle_plans,
        total_distance=round(sum(plan.totalDistance for plan in vehicle_plans), 3),
    )
