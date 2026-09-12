"""客货邮路线规划算法服务（编程组自研，OR-Tools 求解器）。

契约见 docs/api/algorithm-api.yaml；与 mock-algorithm 的关系见 README.md。
"""

import logging
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from .distance import (
    EUCLIDEAN_AVG_SPEED_KMH,
    AmapDistanceProvider,
    AmapUnavailable,
    RouteResult,
    haversine_km,
)
from .models import (
    AlgorithmConfig,
    DistancePair,
    DistanceRequest,
    DistanceResponse,
    ErrorResponse,
    OrderType,
    PlanRequest,
    PlanResult,
    RoutePoint,
    RouteRequest,
    RouteResponse,
    Station,
)
from .solver import solve

logger = logging.getLogger(__name__)

ALGORITHM_VERSION = "haco-cps-1.4.1"
PARAMETER_VERSION = "haco-cps-default-v1.4.1"
BASELINE_VERSION = "ortools-1.3.0"

# 与算法组回复一致的规模上限：100 站点 / 25 订单 / 3 车 / 10 秒计算超时
# 站点上限从 30 放宽到 100：联合调度（公交线路骨架 + 货运绕行）时骨架站点会并入 station 快照，
# 3 条真实公交线路 + 订单站点可达 70~80 站，旧的 30 站上限会整批拒掉。
MAX_STATIONS = 100
MAX_ORDERS = 25
MAX_VEHICLES = 3

# requestId 幂等保留期（契约：24 小时；进程内存储，与 mock 同语义）
IDEMPOTENCY_TTL_SECONDS = 24 * 3600


class JobRecord(BaseModel):
    request: PlanRequest
    result: PlanResult | None = None
    pending: bool = False
    created_at: datetime


app = FastAPI(
    title="客货邮路线规划算法服务",
    version=ALGORITHM_VERSION,
    description="编程组自研路线规划算法服务（OR-Tools 求解器）。接口契约见 docs/api/algorithm-api.yaml。",
)
jobs: dict[str, JobRecord] = {}

# 高德路网距离：配置 AMAP_KEY 即启用（进程内站点级缓存随实例存活）；未配置走欧氏直线
amap_provider = AmapDistanceProvider.from_env()


@app.exception_handler(HTTPException)
async def http_exception_handler(_request: Request, exc: HTTPException) -> JSONResponse:
    if isinstance(exc.detail, dict) and "code" in exc.detail:
        return JSONResponse(status_code=exc.status_code, content=exc.detail)
    return error_response(exc.status_code, "ALGORITHM_INTERNAL_ERROR", str(exc.detail))


def now() -> datetime:
    return datetime.now(timezone.utc)


def error_response(status_code: int, code: str, message: str, request_id: str | None = None) -> JSONResponse:
    payload = ErrorResponse(code=code, message=message, requestId=request_id)
    return JSONResponse(status_code=status_code, content=payload.model_dump(exclude_none=True))


def config_warnings(config: AlgorithmConfig) -> list[str]:
    """ACO 超参数不参与 OR-Tools 求解；超出建议范围仅告警不拒绝（契约 Q8）。"""
    warnings = []
    if config.ant_count > 100:
        warnings.append("ant_count 超出建议范围（建议 <= 100）")
    if config.max_iterations > 500:
        warnings.append("max_iterations 超出建议范围（建议 <= 500）")
    return warnings


def evict_expired_jobs() -> None:
    """惰性清理超过 24 小时幂等保留期的任务记录。"""
    cutoff = now().timestamp() - IDEMPOTENCY_TTL_SECONDS
    expired = [key for key, record in jobs.items() if record.created_at.timestamp() < cutoff]
    for key in expired:
        del jobs[key]


def build_result(request: PlanRequest) -> PlanResult:
    warnings = config_warnings(request.algorithmConfig)
    matrix = None
    # 仅当存在需要求解的任务（orders 或 shipments）才取路网矩阵；
    # 此前只判 orders，纯 shipments 请求会漏走高德路网、退化为欧氏直线。
    if amap_provider is not None and (request.orders or request.shipments):
        try:
            matrix = amap_provider.get_matrix([request.depot, *request.stations])
        except AmapUnavailable:
            # 整单降级回欧氏直线，保证单次求解矩阵口径一致
            warnings.append("路网距离不可用，已降级直线距离")
    outcome = solve(request, matrix)
    # 合并 solver 产生的 warnings（如 HACO_FALLBACK_TO_BASELINE）
    warnings.extend(outcome.warnings)
    distance_unit = "km" if matrix is not None else "degree"
    if outcome.status == "infeasible":
        return PlanResult(
            requestId=request.requestId,
            status="infeasible",
            reasonCode=outcome.reason_code,
            warnings=warnings,
            algorithmVersion=outcome.algorithm_version,
            parameterVersion=outcome.parameter_version,
            distanceUnit=distance_unit,
            computedAt=now(),
        )
    return PlanResult(
        requestId=request.requestId,
        status="feasible",
        warnings=warnings,
        algorithmVersion=outcome.algorithm_version,
        parameterVersion=outcome.parameter_version,
        distanceUnit=distance_unit,
        totalDistance=outcome.total_distance,
        vehiclePlans=outcome.vehicle_plans,
        computedAt=now(),
    )


def validate_request(request: PlanRequest) -> JSONResponse | None:
    # 规模上限：orders 与 shipments 均计入订单数（每个 shipment 展开为 PICKUP+DELIVERY 两节点）
    total_orders = len(request.orders) + len(request.shipments)
    if len(request.stations) > MAX_STATIONS or total_orders > MAX_ORDERS or len(request.vehicles) > MAX_VEHICLES:
        return error_response(413, "OVER_LIMIT", "超出规模上限（30 站点 / 25 订单 / 3 车）", request.requestId)
    if not request.vehicles:
        return error_response(400, "INVALID_INPUT", "至少需要一台可用车辆", request.requestId)

    known_stations = {station.stationId for station in request.stations} | {request.depot.stationId}
    for order in request.orders:
        if order.orderType == OrderType.PASSENGER:
            if not order.boardingStationId or not order.alightingStationId:
                return error_response(400, "INVALID_INPUT", f"客运订单 {order.orderId} 缺少上车站或下车站", request.requestId)
            refs = [order.boardingStationId, order.alightingStationId]
        else:
            if not order.stationId:
                return error_response(400, "INVALID_INPUT", f"订单 {order.orderId} 缺少作业站点", request.requestId)
            refs = [order.stationId]
        unknown = [station_id for station_id in refs if station_id not in known_stations]
        if unknown:
            return error_response(400, "INVALID_INPUT", f"订单 {order.orderId} 引用了未知站点 {unknown}", request.requestId)

    # PlanShipment：校验揽收/送达站点引用（未知站点会触发 solver KeyError 崩溃），
    # 且揽收站与送达站必须不同（配对语义要求）。
    for shipment in request.shipments:
        if shipment.pickupStationId == shipment.deliveryStationId:
            return error_response(400, "INVALID_INPUT", f"货运订单 {shipment.shipmentId} 揽收站与送达站相同", request.requestId)
        unknown = [station_id for station_id in (shipment.pickupStationId, shipment.deliveryStationId)
                   if station_id not in known_stations]
        if unknown:
            return error_response(400, "INVALID_INPUT", f"货运订单 {shipment.shipmentId} 引用了未知站点 {unknown}", request.requestId)
    return None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "algorithmVersion": ALGORITHM_VERSION}


# 就绪状态：本服务为 OR-Tools 求解器、无模型加载，import 成功即就绪（恒 True）。
# 契约 Q11 要求「未就绪返回 503」，本实现无模型加载故 503 分支不触发；
# 保留该标志供未来引入模型/依赖加载时切换为「加载完成前置 False」。
_ready = True


@app.get("/ready")
def ready():
    if not _ready:
        return JSONResponse(status_code=503, content={"status": "NOT_READY", "algorithmVersion": ALGORITHM_VERSION})
    return {"status": "READY", "algorithmVersion": ALGORITHM_VERSION}


@app.post(
    "/api/v1/plan",
    response_model=PlanResult,
    response_model_exclude_none=True,
    responses={
        400: {"model": ErrorResponse},
        408: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
def create_plan(request: PlanRequest):
    evict_expired_jobs()
    record = jobs.get(request.requestId)
    if record is not None:
        if record.pending:
            return error_response(408, "TIMEOUT", "任务仍在计算中，请轮询 /api/v1/result/{requestId}", request.requestId)
        return record.result.model_copy(update={"cached": True})

    validation_error = validate_request(request)
    if validation_error is not None:
        return validation_error

    # scenario 为 Mock 专属混沌字段，真实算法接受但忽略（契约标注"真实算法可忽略"）。
    # 本规模求解远低于契约 10 秒时限，同步返回；若未来出现超时，按契约先落 pending
    # 记录并返回 408，业务侧凭 requestId 轮询 /api/v1/result/{requestId}。
    try:
        result = build_result(request)
    except Exception as exc:
        # 求解器异常（如未预见的 KeyError/索引越界）不得裸 500：返回结构化错误，
        # 后端适配层凭 code=ALGORITHM_INTERNAL_ERROR 识别并降级，而非拿到无业务码的 500。
        logger.exception("规划求解异常 requestId=%s", request.requestId)
        return error_response(500, "ALGORITHM_INTERNAL_ERROR", f"算法求解失败：{exc}", request.requestId)
    jobs[request.requestId] = JobRecord(request=request, result=result, created_at=now())
    return result


@app.post(
    "/api/v1/distance",
    response_model=DistanceResponse,
    response_model_exclude_none=True,
    responses={
        400: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
def get_distance(request: DistanceRequest):
    """两站点间距离/耗时查询（寄货页取货→送达）。

    复用高德路网矩阵（AmapDistanceProvider）；未配置 AMAP_KEY 或高德不可用时降级
    欧氏直线估算（恒返回 distanceUnit=km；durationSeconds 按均速估算，非 null）。
    """
    if len(request.stations) != 2:
        return error_response(400, "INVALID_INPUT", "距离查询需要且仅需要 2 个站点", request.requestId)
    points = [Station(stationId=s.stationId, longitude=s.longitude, latitude=s.latitude) for s in request.stations]
    origin, destination = points[0], points[1]
    if amap_provider is not None:
        route = amap_provider.get_route(origin, destination)
    else:
        # 未配置 AMAP_KEY：直线估算（provider=euclidean），恒 km
        km = haversine_km(origin.longitude, origin.latitude, destination.longitude, destination.latitude)
        seconds = round(km / EUCLIDEAN_AVG_SPEED_KMH * 3600)
        route = RouteResult(available=True, distanceKm=round(km, 2), durationSeconds=seconds, provider="euclidean")
    pair = DistancePair(
        fromStationId=origin.stationId,
        toStationId=destination.stationId,
        distanceKm=route.distanceKm,
        durationSeconds=route.durationSeconds,
        provider=route.provider,
        available=route.available,
    )
    return DistanceResponse(requestId=request.requestId, distanceUnit="km", pairs=[pair], computedAt=now())


@app.post(
    "/api/v1/route",
    response_model=RouteResponse,
    response_model_exclude_none=True,
    responses={
        400: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
def get_route(request: RouteRequest):
    """坐标 → 坐标单路线查询（实时公交 ETA：车辆位置 → 下一站）。

    复用 AmapDistanceProvider.get_route（与派单/寄货同一套高德路网实现，不重复实现 HTTP）。
    未配置 AMAP_KEY / 高德失败 → euclidean 直线估算（provider 明确标注）；明确不可达 → available=false。
    """
    origin = Station(stationId="origin", longitude=request.origin.longitude, latitude=request.origin.latitude)
    destination = Station(stationId="destination",
                          longitude=request.destination.longitude, latitude=request.destination.latitude)
    if amap_provider is not None:
        # 含真实道路 polyline（Phase 6 RoadSegment）；失败/超时/未配 key 均回退直线（provider 明确标注）
        result = amap_provider.get_route_with_polyline(origin, destination)
    else:
        km = haversine_km(origin.longitude, origin.latitude, destination.longitude, destination.latitude)
        seconds = round(km / EUCLIDEAN_AVG_SPEED_KMH * 3600)
        result = RouteResult(available=True, distanceKm=round(km, 2), durationSeconds=seconds, provider="euclidean",
                             polyline=[RoutePoint(longitude=origin.longitude, latitude=origin.latitude),
                                       RoutePoint(longitude=destination.longitude, latitude=destination.latitude)])
    if not result.available:
        return RouteResponse(available=False, provider="amap", reasonCode="ROUTE_UNAVAILABLE")
    return RouteResponse(available=True, distanceKm=result.distanceKm,
                         durationSeconds=result.durationSeconds, provider=result.provider,
                         polyline=result.polyline)


@app.get(
    "/api/v1/result/{request_id}",
    response_model=PlanResult,
    response_model_exclude_none=True,
    responses={202: {"description": "仍在计算中"}, 404: {"model": ErrorResponse}},
)
def get_plan_result(request_id: str):
    evict_expired_jobs()
    record = jobs.get(request_id)
    if record is None:
        return error_response(404, "REQUEST_NOT_FOUND", "requestId 不存在或已超过 24 小时幂等保留期", request_id)
    if record.pending:
        return JSONResponse(status_code=202, content={"requestId": request_id, "status": "computing"})
    return record.result
