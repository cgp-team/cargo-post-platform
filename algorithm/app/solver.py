"""OR-Tools 路线规划求解器（pywrapcp 路由模型）。

模型要点：
- 节点：0 为场站；每个客运订单拆为上车站节点 + 下车站节点（强制同车、先上后下），
  派送/揽收订单各占一个作业站点节点。
- 容量双维度累计约束：载客维度 BOARD +1（批次内座位不复用），载货维度
  DELIVER / PICKUP 均 +itemCount（派送件占仓位整个车次），单车次累计量
  分别 ≤ passengerCapacity / cargoCapacity，与 OVER_CAPACITY 预检口径一致。
- 优先单车（契约 Q7）：每台启用车辆计大额固定成本，目标函数先最小化用车数、再最小化总里程。
- 距离：默认 GCJ-02 坐标两点欧氏直线（单位：度）；调用方注入距离矩阵（如高德路网，
  单位：公里）时按矩阵查表。距离乘以 DISTANCE_SCALE 取整后作为求解成本，输出里程
  换算回原单位，保留 3 位小数，与 mock 口径一致。
- 确定性：仅用确定性的首解构造策略（PARALLEL_CHEAPEST_INSERTION），不引入任何
  随机元启发式或 wall-clock 依赖，同输入必然同输出。
"""

from dataclasses import dataclass, field
from math import hypot

from ortools.constraint_solver import pywrapcp, routing_enums_pb2

from .distance import DistanceMatrix
from .models import OrderType, PlanRequest, RouteStop, StopAction, VehiclePlan

# 距离（度或公里，随距离提供方）× 1000 取整作为整数成本，对应输出保留 3 位小数
DISTANCE_SCALE = 1000
# 远大于任何可行路线的里程成本（30 节点 × 单段最大约几十度的千倍），
# 使目标函数等价于"先最小化用车数，再最小化总里程"
VEHICLE_FIXED_COST = 10**9
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
    """
    passengers = sum(1 for order in request.orders if order.orderType == OrderType.PASSENGER)
    deliveries = sum(order.itemCount for order in request.orders if order.orderType == OrderType.DELIVERY)
    pickups = sum(order.itemCount for order in request.orders if order.orderType == OrderType.PICKUP)
    return passengers, deliveries, pickups


def solve(request: PlanRequest, matrix: DistanceMatrix | None = None) -> SolveOutcome:
    """求解一个批次的路线规划。契约口径：总需求超总容量 → OVER_CAPACITY；

    其余无解（时序矛盾等）→ TIMING_CONFLICT；不返回部分方案。
    matrix 为 None 时用两点欧氏直线（度）；注入矩阵（如高德路网，公里）时按站点对查表，
    成本缩放与输出换算逻辑不变，segmentDistance/totalDistance 跟随矩阵单位。
    """
    passengers, deliveries, pickups = _total_demand(request)
    if not request.orders:
        return SolveOutcome(status="feasible")
    total_passenger_capacity = sum(v.passengerCapacity for v in request.vehicles)
    total_cargo_capacity = sum(v.cargoCapacity for v in request.vehicles)
    if passengers > total_passenger_capacity or deliveries > total_cargo_capacity or pickups > total_cargo_capacity:
        return SolveOutcome(status="infeasible", reason_code="OVER_CAPACITY")

    station_map = {station.stationId: station for station in request.stations}
    station_map[request.depot.stationId] = request.depot

    # 节点 0 为场站，其后按订单展开作业节点
    nodes: list[_Node] = [_Node(station_id=request.depot.stationId, action=StopAction.DEPART)]
    passenger_pairs: list[tuple[int, int]] = []  # (board_node, alight_node)
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

    def scaled_distance(from_station, to_station) -> int:
        if matrix is None:
            return _scaled_distance(from_station, to_station)
        return int(round(matrix[(from_station.stationId, to_station.stationId)][0] * DISTANCE_SCALE))

    def segment_seconds(from_station, to_station) -> float | None:
        """分段路网行驶秒数（仅高德矩阵路径；欧氏路径为 None）。"""
        if matrix is None:
            return None
        return matrix[(from_station.stationId, to_station.stationId)][1]

    def distance_callback(from_index: int, to_index: int) -> int:
        from_node = manager.IndexToNode(from_index)
        to_node = manager.IndexToNode(to_index)
        return scaled_distance(station_map[nodes[from_node].station_id], station_map[nodes[to_node].station_id])

    distance_callback_index = routing.RegisterTransitCallback(distance_callback)
    routing.SetArcCostEvaluatorOfAllVehicles(distance_callback_index)
    for vehicle_index in range(num_vehicles):
        routing.SetFixedCostOfVehicle(VEHICLE_FIXED_COST, vehicle_index)

    # 载客维度：BOARD +1（下车不释放座位），单车次累计载客 ≤ passengerCapacity。
    # 契约/任务口径为"双维度累计约束"：批次内座位不复用，与 OVER_CAPACITY 预检
    # （总需求 vs 总容量）语义一致——6 名乘客即需双车，不靠穿插上下客复用座位。
    def passenger_demand(node: int) -> int:
        return 1 if nodes[node].action == StopAction.BOARD else 0

    passenger_callback_index = routing.RegisterUnaryTransitCallback(
        lambda from_index: passenger_demand(manager.IndexToNode(from_index))
    )
    routing.AddDimensionWithVehicleCapacity(
        passenger_callback_index,
        0,
        [v.passengerCapacity for v in request.vehicles],
        True,
        "Passenger",
    )

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
        while True:
            next_index = solution.Value(routing.NextVar(index))
            # 不用 GetArcCostForVehicle：车辆固定成本会摊入首段弧成本，里程必须纯按距离矩阵
            from_station = station_map[nodes[manager.IndexToNode(index)].station_id]
            if routing.IsEnd(next_index):
                to_station = request.depot
            else:
                to_station = station_map[nodes[manager.IndexToNode(next_index)].station_id]
            segment = scaled_distance(from_station, to_station)
            scaled_total += segment
            if routing.IsEnd(next_index):
                stops.append(
                    RouteStop(
                        stationId=request.depot.stationId,
                        action=StopAction.RETURN,
                        segmentDistance=segment / DISTANCE_SCALE,
                        segmentDuration=segment_seconds(from_station, to_station),
                    )
                )
                break
            node = nodes[manager.IndexToNode(next_index)]
            segment_km = segment / DISTANCE_SCALE
            seg_sec = segment_seconds(from_station, to_station)
            route_stop: dict = {
                "stationId": node.station_id,
                "orderId": node.order_id,
                "action": node.action,
                "segmentDistance": segment_km,
                "segmentDuration": seg_sec,
            }
            # 算法解释（仅货运/揽收经停）：accepted/serviceMode/servicePoint/detour（Phase 5）
            if node.order_id and node.action in (StopAction.PICKUP, StopAction.DELIVER):
                on_skeleton = node.station_id in skeleton_sets.get(vehicle_index, set())
                route_stop.update(
                    accepted=True,
                    serviceMode="NEAREST_STATION",
                    servicePoint=node.station_id,
                    detourDistance=0.0 if on_skeleton else segment_km,
                    detourDuration=0.0 if on_skeleton else (seg_sec if seg_sec is not None else 0.0),
                    passengerImpact=0.0,
                    reasonCode=None,
                )
            stops.append(RouteStop(**route_stop))
            index = next_index
        vehicle_plans.append(
            VehiclePlan(
                vehicleId=vehicle.vehicleId,
                stops=stops,
                totalDistance=scaled_total / DISTANCE_SCALE,
            )
        )

    return SolveOutcome(
        status="feasible",
        vehicle_plans=vehicle_plans,
        total_distance=round(sum(plan.totalDistance for plan in vehicle_plans), 3),
    )
