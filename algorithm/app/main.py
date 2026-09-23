"""客货邮路线规划算法服务（编程组自研，OR-Tools 求解器）。

契约见 docs/api/algorithm-api.yaml。
"""

import asyncio
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
from .dispatch_opt.runtime import DispatchAllocatePayload, allocate_dispatch

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

# 契约 10 秒计算超时（docs/api/algorithm-api.yaml）：超时后转异步轮询 /api/v1/result/{requestId}
SOLVE_TIMEOUT_SECONDS = 10.0

# 进程内任务记录上限：防长时间运行 jobs 字典内存单调增长（超出后按创建时间淘汰最旧）
MAX_JOBS = 1000


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
    """惰性清理超过 24 小时幂等保留期的任务记录，并限制总量上限。"""
    cutoff = now().timestamp() - IDEMPOTENCY_TTL_SECONDS
    expired = [key for key, record in jobs.items() if record.created_at.timestamp() < cutoff]
    for key in expired:
        del jobs[key]
    # 总量上限兜底：即使未到 24h 过期，超限也按创建时间淘汰最旧，防内存单调增长
    if len(jobs) > MAX_JOBS:
        oldest = sorted(jobs, key=lambda key: jobs[key].created_at)[: len(jobs) - MAX_JOBS]
        for key in oldest:
            del jobs[key]


def build_result(request: PlanRequest) -> PlanResult:
    warnings = config_warnings(request.algorithmConfig)
    matrix = None
    matrix_source = "none"
    # 仅当存在需要求解的任务（orders 或 shipments）才取路网矩阵；
    # 此前只判 orders，纯 shipments 请求会漏走高德路网、退化为欧氏直线。
    if request.orders or request.shipments:
        pts = [request.depot, *request.stations]
        try:
            if amap_provider is not None:
                matrix = amap_provider.get_matrix(pts)
                if matrix is not None:
                    matrix_source = "amap"
        except AmapUnavailable:
            warnings.append("高德路网不可用")
        if matrix is None:
            # DISPATCH_CORE_V047.1：正式道路矩阵（GraphHopper/OSM）；拿不到 formal 不冒充
            try:
                from .dispatch_opt.route_cost_provider import build_formal_distance_matrix

                coords = [(s.latitude, s.longitude) for s in pts]
                formal_m, reason = build_formal_distance_matrix(coords)
                if formal_m is not None:
                    matrix = formal_m
                    matrix_source = "formal_road"
                else:
                    warnings.append(f"正式路网矩阵不可用（{reason}），禁止把直线当正式成本")
            except Exception as ex:  # noqa: BLE001
                warnings.append(f"正式路网矩阵异常：{type(ex).__name__}")
    outcome = solve(request, matrix)
    # 合并 solver 产生的 warnings（如 HACO_FALLBACK_TO_BASELINE）
    # 各求解器（baseline / HACO-1.4 / hybrid）返回的 SolveOutcome 字段集不完全一致，
    # 统一按「缺失即默认」读取，避免某个求解器少一个字段就把 200 打成 500。
    warnings.extend(getattr(outcome, "warnings", None) or [])
    if matrix is not None and matrix_source == "amap":
        distance_unit = "km"
    elif matrix is not None and matrix_source == "formal_road":
        distance_unit = "km"
    else:
        distance_unit = "degree"
        if request.orders or request.shipments:
            warnings.append("FORMAL_COST_UNAVAILABLE：当前距离为非正式口径，不得当作真实道路成本")
    if outcome.status == "infeasible":
        return PlanResult(
            requestId=request.requestId,
            status="infeasible",
            reasonCode=outcome.reason_code,
            warnings=warnings,
            algorithmVersion=getattr(outcome, "algorithm_version", ALGORITHM_VERSION),
            parameterVersion=getattr(outcome, "parameter_version", PARAMETER_VERSION),
            distanceUnit=distance_unit,
            unassignedOrderIds=getattr(outcome, "unassigned_order_ids", None) or [],
            computedAt=now(),
        )
    return PlanResult(
        requestId=request.requestId,
        status="feasible",
        warnings=warnings,
        algorithmVersion=getattr(outcome, "algorithm_version", ALGORITHM_VERSION),
        parameterVersion=getattr(outcome, "parameter_version", PARAMETER_VERSION),
        distanceUnit=distance_unit,
        totalDistance=getattr(outcome, "total_distance", 0.0),
        vehiclePlans=getattr(outcome, "vehicle_plans", None) or [],
        unassignedOrderIds=getattr(outcome, "unassigned_order_ids", None) or [],
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
            if order.boardingStationId == order.alightingStationId:
                return error_response(400, "INVALID_INPUT", f"客运订单 {order.orderId} 上车站与下车站相同", request.requestId)
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
async def create_plan(request: PlanRequest):
    evict_expired_jobs()
    record = jobs.get(request.requestId)
    if record is not None:
        if record.pending:
            return error_response(408, "TIMEOUT", "任务仍在计算中，请轮询 /api/v1/result/{requestId}", request.requestId)
        return record.result.model_copy(update={"cached": True})

    validation_error = validate_request(request)
    if validation_error is not None:
        return validation_error

    # 先落 pending 记录：并发重入时上方 jobs.get 命中 pending → 408，避免重复求解
    record = JobRecord(request=request, pending=True, created_at=now())
    jobs[request.requestId] = record

    # 在线程池求解，主协程 wait_for 10 秒；超时后后台线程继续，完成后写回结果供轮询
    task = asyncio.create_task(asyncio.to_thread(build_result, request))

    def _store_result(done: asyncio.Future) -> None:
        # 后台求解完成后写回结果；异常时落结构化错误结果，业务侧轮询 /result 时拿到而非裸 500
        try:
            record.result = done.result()
        except Exception as exc:
            logger.exception("规划求解异常 requestId=%s", request.requestId)
            record.result = PlanResult(
                requestId=request.requestId,
                status="error",
                reasonCode="ALGORITHM_INTERNAL_ERROR",
                warnings=[f"算法求解失败：{exc}"],
                algorithmVersion=ALGORITHM_VERSION,
                parameterVersion=PARAMETER_VERSION,
                computedAt=now(),
            )
        finally:
            record.pending = False

    task.add_done_callback(_store_result)

    try:
        result = await asyncio.wait_for(asyncio.shield(task), timeout=SOLVE_TIMEOUT_SECONDS)
    except asyncio.TimeoutError:
        # 后台继续计算，完成后由 _store_result 写回；业务侧凭 requestId 轮询 /api/v1/result/{requestId}
        logger.warning("规划求解超时 requestId=%s，转异步轮询", request.requestId)
        return error_response(408, "TIMEOUT", "任务仍在计算中，请轮询 /api/v1/result/{requestId}", request.requestId)
    except Exception as exc:
        # 求解器异常（如未预见的 KeyError/索引越界）不得裸 500：返回结构化错误，
        # 后端适配层凭 code=ALGORITHM_INTERNAL_ERROR 识别并降级，而非拿到无业务码的 500。
        logger.exception("规划求解异常 requestId=%s", request.requestId)
        return error_response(500, "ALGORITHM_INTERNAL_ERROR", f"算法求解失败：{exc}", request.requestId)
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


@app.post("/api/v1/dispatch/allocate", response_model=None)
def dispatch_allocate(payload: DispatchAllocatePayload) -> dict:
    """新订单动态调度唯一主入口：Candidate → Reachability → TripLock → MarginalCost
    → Passenger/SLA → Economic Admission → Compare → DispatchPlan → Decision Trace.

    正式路由成本由 RouteCostProvider 给出；拿不到正式真实道路时显式返回 UNKNOWN，
    不会用直线距离冒充正式成本。
    """
    return allocate_dispatch(payload)


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
