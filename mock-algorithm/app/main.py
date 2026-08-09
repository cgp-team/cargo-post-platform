from datetime import datetime, timezone
from enum import Enum
from math import hypot
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

ALGORITHM_VERSION = "mock-2.0.0"
PARAMETER_VERSION = "default-v1"

# 与算法组回复一致的规模上限：30 站点 / 25 订单 / 3 车 / 10 秒计算超时
MAX_STATIONS = 30
MAX_ORDERS = 25
MAX_VEHICLES = 3


class Scenario(str, Enum):
    SUCCESS = "SUCCESS"
    PARTIAL_REJECTION = "PARTIAL_REJECTION"
    NO_FEASIBLE_SOLUTION = "NO_FEASIBLE_SOLUTION"
    TIMEOUT = "TIMEOUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class OrderType(str, Enum):
    PASSENGER = "PASSENGER"
    DELIVERY = "DELIVERY"
    PICKUP = "PICKUP"


class StopAction(str, Enum):
    DEPART = "DEPART"
    BOARD = "BOARD"
    ALIGHT = "ALIGHT"
    DELIVER = "DELIVER"
    PICKUP = "PICKUP"
    RETURN = "RETURN"


class Station(BaseModel):
    stationId: str
    longitude: float
    latitude: float


class Vehicle(BaseModel):
    vehicleId: int
    passengerCapacity: int = Field(default=5, ge=1)
    cargoCapacity: int = Field(default=4, ge=1)


class PlanOrder(BaseModel):
    orderId: str
    orderType: OrderType
    boardingStationId: str | None = None
    alightingStationId: str | None = None
    stationId: str | None = None
    itemCount: int = Field(default=1, ge=1)
    weightKg: float | None = Field(default=None, ge=0)
    volumeM3: float | None = Field(default=None, ge=0)


class AlgorithmConfig(BaseModel):
    model_config = ConfigDict(extra="allow")

    ant_count: int = 30
    max_iterations: int = 100
    alpha: float = 1.0
    beta: float = 3.0
    rho: float = 0.1
    Q: float = 100
    convergence_threshold: int = 20


class PlanRequest(BaseModel):
    requestId: str
    batchStart: datetime
    batchEnd: datetime
    depot: Station
    stations: list[Station]
    vehicles: list[Vehicle]
    orders: list[PlanOrder]
    algorithmConfig: AlgorithmConfig = Field(default_factory=AlgorithmConfig)
    scenario: Scenario = Scenario.SUCCESS

    @field_validator("batchStart", "batchEnd")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("time must include a timezone")
        return value


class RouteStop(BaseModel):
    stationId: str
    orderId: str | None = None
    action: StopAction
    segmentDistance: float = 0.0


class VehiclePlan(BaseModel):
    vehicleId: int
    stops: list[RouteStop]
    totalDistance: float


class PlanResult(BaseModel):
    requestId: str
    status: str
    reasonCode: str | None = None
    cached: bool = False
    warnings: list[str] = Field(default_factory=list)
    algorithmVersion: str
    parameterVersion: str
    totalDistance: float = 0.0
    vehiclePlans: list[VehiclePlan] = Field(default_factory=list)
    computedAt: datetime


class ErrorResponse(BaseModel):
    code: str
    message: str
    requestId: str | None = None
    details: dict[str, Any] | None = None


class JobRecord(BaseModel):
    request: PlanRequest
    result: PlanResult | None = None
    pending: bool = False
    polls: int = 0


app = FastAPI(
    title="客货邮路线规划 Mock 服务",
    version=ALGORITHM_VERSION,
    description="仅用于协议联调，不实现真实路径规划。接口契约见 docs/api/algorithm-api.yaml。",
)
jobs: dict[str, JobRecord] = {}


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
    warnings = []
    if config.ant_count > 100:
        warnings.append("ant_count 超出建议范围（建议 <= 100）")
    if config.max_iterations > 500:
        warnings.append("max_iterations 超出建议范围（建议 <= 500）")
    return warnings


def euclidean(a: Station, b: Station) -> float:
    return round(hypot(a.longitude - b.longitude, a.latitude - b.latitude), 3)


def demand(request: PlanRequest) -> tuple[int, int]:
    passengers = sum(1 for order in request.orders if order.orderType == OrderType.PASSENGER)
    cargo = sum(order.itemCount for order in request.orders if order.orderType != OrderType.PASSENGER)
    return passengers, cargo


def build_result(request: PlanRequest) -> PlanResult:
    """按"优先单车、装不下自动加车"的规则生成确定性 Mock 方案，不做真实寻优。"""
    passengers, cargo = demand(request)
    used: list[Vehicle] = []
    covered_passengers = covered_cargo = 0
    for vehicle in request.vehicles:
        if covered_passengers >= passengers and covered_cargo >= cargo:
            break
        used.append(vehicle)
        covered_passengers += vehicle.passengerCapacity
        covered_cargo += vehicle.cargoCapacity

    if covered_passengers < passengers or covered_cargo < cargo:
        return PlanResult(
            requestId=request.requestId,
            status="infeasible",
            reasonCode="OVER_CAPACITY",
            algorithmVersion=ALGORITHM_VERSION,
            parameterVersion=PARAMETER_VERSION,
            computedAt=now(),
        )

    station_map = {station.stationId: station for station in request.stations}
    station_map[request.depot.stationId] = request.depot

    assignments: dict[int, list[PlanOrder]] = {vehicle.vehicleId: [] for vehicle in used}
    for index, order in enumerate(request.orders):
        vehicle = used[index % len(used)]
        assignments[vehicle.vehicleId].append(order)

    vehicle_plans: list[VehiclePlan] = []
    for vehicle in used:
        stops = [RouteStop(stationId=request.depot.stationId, action=StopAction.DEPART)]
        previous = request.depot
        for order in assignments[vehicle.vehicleId]:
            order_stops: list[RouteStop] = []
            if order.orderType == OrderType.PASSENGER:
                order_stops = [
                    RouteStop(stationId=order.boardingStationId, orderId=order.orderId, action=StopAction.BOARD),
                    RouteStop(stationId=order.alightingStationId, orderId=order.orderId, action=StopAction.ALIGHT),
                ]
            elif order.orderType == OrderType.DELIVERY:
                order_stops = [
                    RouteStop(stationId=order.stationId, orderId=order.orderId, action=StopAction.DELIVER),
                ]
            else:
                order_stops = [
                    RouteStop(stationId=order.stationId, orderId=order.orderId, action=StopAction.PICKUP),
                ]
            for stop in order_stops:
                target = station_map[stop.stationId]
                stop.segmentDistance = euclidean(previous, target)
                previous = target
                stops.append(stop)
        stops.append(
            RouteStop(
                stationId=request.depot.stationId,
                action=StopAction.RETURN,
                segmentDistance=euclidean(previous, request.depot),
            )
        )
        vehicle_plans.append(
            VehiclePlan(
                vehicleId=vehicle.vehicleId,
                stops=stops,
                totalDistance=round(sum(stop.segmentDistance for stop in stops), 3),
            )
        )

    return PlanResult(
        requestId=request.requestId,
        status="feasible",
        warnings=config_warnings(request.algorithmConfig),
        algorithmVersion=ALGORITHM_VERSION,
        parameterVersion=PARAMETER_VERSION,
        totalDistance=round(sum(plan.totalDistance for plan in vehicle_plans), 3),
        vehiclePlans=vehicle_plans,
        computedAt=now(),
    )


def infeasible_result(request: PlanRequest, reason_code: str) -> PlanResult:
    return PlanResult(
        requestId=request.requestId,
        status="infeasible",
        reasonCode=reason_code,
        algorithmVersion=ALGORITHM_VERSION,
        parameterVersion=PARAMETER_VERSION,
        computedAt=now(),
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "algorithmVersion": ALGORITHM_VERSION}


@app.get("/ready")
def ready() -> dict[str, str]:
    return {"status": "READY", "algorithmVersion": ALGORITHM_VERSION}


@app.post(
    "/api/v1/plan",
    response_model=PlanResult,
    response_model_exclude_none=True,
    responses={
        400: {"model": ErrorResponse},
        408: {"model": ErrorResponse},
        413: {"model": ErrorResponse},
        422: {"model": ErrorResponse},
        500: {"model": ErrorResponse},
        503: {"model": ErrorResponse},
    },
)
def create_plan(request: PlanRequest):
    record = jobs.get(request.requestId)
    if record is not None:
        if record.pending:
            return error_response(408, "TIMEOUT", "任务仍在计算中，请轮询 /api/v1/result/{requestId}", request.requestId)
        return record.result.model_copy(update={"cached": True})

    if request.scenario == Scenario.INTERNAL_ERROR:
        return error_response(500, "ALGORITHM_INTERNAL_ERROR", "Mock internal error", request.requestId)
    if len(request.stations) > MAX_STATIONS or len(request.orders) > MAX_ORDERS or len(request.vehicles) > MAX_VEHICLES:
        return error_response(413, "OVER_LIMIT", "超出规模上限（30 站点 / 25 订单 / 3 车）", request.requestId)
    if not request.vehicles:
        return error_response(400, "INVALID_INPUT", "至少需要一台可用车辆", request.requestId)

    known_stations = {station.stationId for station in request.stations} | {request.depot.stationId}
    for order in request.orders:
        refs = []
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

    if request.scenario == Scenario.TIMEOUT:
        jobs[request.requestId] = JobRecord(request=request, pending=True)
        return error_response(408, "TIMEOUT", "计算超时（10 秒），请凭 requestId 轮询结果", request.requestId)
    if request.scenario == Scenario.NO_FEASIBLE_SOLUTION:
        result = infeasible_result(request, "TIMING_CONFLICT")
    elif request.scenario == Scenario.PARTIAL_REJECTION:
        result = infeasible_result(request, "PARTIAL_ONLY")
    else:
        result = build_result(request)

    jobs[request.requestId] = JobRecord(request=request, result=result)
    return result


@app.get(
    "/api/v1/result/{request_id}",
    response_model=PlanResult,
    response_model_exclude_none=True,
    responses={202: {"description": "仍在计算中"}, 404: {"model": ErrorResponse}},
)
def get_plan_result(request_id: str):
    record = jobs.get(request_id)
    if record is None:
        return error_response(404, "REQUEST_NOT_FOUND", "requestId 不存在或已超过 24 小时幂等保留期", request_id)
    if record.pending:
        record.polls += 1
        if record.polls == 1:
            return JSONResponse(status_code=202, content={"requestId": request_id, "status": "computing"})
        record.pending = False
        record.result = build_result(record.request)
    return record.result
