from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

ALGORITHM_VERSION = "mock-1.0.0"


class Scenario(str, Enum):
    SUCCESS = "SUCCESS"
    PARTIAL_REJECTION = "PARTIAL_REJECTION"
    NO_FEASIBLE_SOLUTION = "NO_FEASIBLE_SOLUTION"
    TIMEOUT = "TIMEOUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class PlanningStatus(str, Enum):
    ACCEPTED = "ACCEPTED"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    PARTIAL_SUCCESS = "PARTIAL_SUCCESS"
    FAILED = "FAILED"
    TIMEOUT = "TIMEOUT"
    NO_FEASIBLE_SOLUTION = "NO_FEASIBLE_SOLUTION"


class AlgorithmConfig(BaseModel):
    model_config = ConfigDict(extra="allow")

    algorithmVersion: str = ALGORITHM_VERSION
    parameterVersion: str = "default-v1"
    maxCalculationSeconds: int | None = Field(default=None, ge=1)


class EstimatedArrival(BaseModel):
    stationId: int
    estimatedArrivalTime: datetime


class VehicleState(BaseModel):
    vehicleId: int
    shiftId: int | None = None
    longitude: float
    latitude: float
    roadNodeId: str | None = None
    roadEdgeId: str | None = None
    passengerLoad: int = Field(ge=0)
    remainingCargoCapacityKg: float = Field(ge=0)
    status: str
    estimatedArrivals: list[EstimatedArrival] = Field(default_factory=list)


class PlanningOrder(BaseModel):
    orderId: str
    orderType: str
    pickupNodeId: str
    deliveryNodeId: str
    itemCount: int = Field(ge=1)
    weightKg: float = Field(ge=0)
    volumeM3: float = Field(ge=0)
    earliestPickupTime: datetime
    latestDeliveryTime: datetime
    priority: int = Field(ge=0)

    @field_validator("earliestPickupTime", "latestDeliveryTime")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("time must include a timezone")
        return value


class PlanningJobRequest(BaseModel):
    requestId: str
    snapshotId: str
    planningTime: datetime
    rollingWindowMinutes: int = Field(ge=1)
    scenario: Scenario = Scenario.SUCCESS
    algorithmConfig: AlgorithmConfig
    vehicles: list[VehicleState]
    orders: list[PlanningOrder]

    @field_validator("planningTime")
    @classmethod
    def require_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("planningTime must include a timezone")
        return value


class PlanningJobAcceptedResponse(BaseModel):
    jobId: str
    requestId: str
    snapshotId: str
    status: PlanningStatus
    acceptedAt: datetime


class ErrorResponse(BaseModel):
    code: str
    message: str
    requestId: str | None = None
    details: dict[str, Any] | None = None


class PlanningJobStatusResponse(BaseModel):
    jobId: str
    requestId: str
    snapshotId: str
    status: PlanningStatus
    algorithmVersion: str
    parameterVersion: str
    updatedAt: datetime
    error: ErrorResponse | None = None


class RouteNode(BaseModel):
    nodeId: str
    orderId: str | None = None
    action: str
    longitude: float
    latitude: float
    estimatedArrivalTime: datetime


class VehiclePlan(BaseModel):
    vehicleId: int
    shiftId: int | None = None
    assignedOrderIds: list[str]
    routeNodes: list[RouteNode]


class RejectedOrder(BaseModel):
    orderId: str
    reasonCode: str
    reasonMessage: str


class Metrics(BaseModel):
    extraDistanceKm: float
    estimatedPassengerDelayMinutes: float
    estimatedRevenue: float
    estimatedCost: float


class PlanningJobResultResponse(BaseModel):
    jobId: str
    requestId: str
    snapshotId: str
    algorithmVersion: str
    parameterVersion: str
    status: PlanningStatus
    score: float
    vehiclePlans: list[VehiclePlan]
    rejectedOrders: list[RejectedOrder]
    metrics: Metrics


class JobRecord(BaseModel):
    request: PlanningJobRequest
    jobId: str
    status: PlanningStatus
    acceptedAt: datetime
    statusQueries: int = 0


app = FastAPI(
    title="客货邮路线规划 Mock 服务",
    version=ALGORITHM_VERSION,
    description="仅用于协议联调，不实现真实路径规划。",
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


def terminal_status(scenario: Scenario) -> PlanningStatus:
    return {
        Scenario.SUCCESS: PlanningStatus.SUCCESS,
        Scenario.PARTIAL_REJECTION: PlanningStatus.PARTIAL_SUCCESS,
        Scenario.NO_FEASIBLE_SOLUTION: PlanningStatus.NO_FEASIBLE_SOLUTION,
        Scenario.TIMEOUT: PlanningStatus.TIMEOUT,
        Scenario.INTERNAL_ERROR: PlanningStatus.FAILED,
    }[scenario]


def advance(record: JobRecord) -> None:
    record.statusQueries += 1
    record.status = PlanningStatus.RUNNING if record.statusQueries == 1 else terminal_status(record.request.scenario)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "algorithmVersion": ALGORITHM_VERSION}


@app.post(
    "/api/algorithm/v1/planning-jobs",
    response_model=PlanningJobAcceptedResponse,
    status_code=202,
    responses={400: {"model": ErrorResponse}, 500: {"model": ErrorResponse}},
)
def create_planning_job(request: PlanningJobRequest):
    if request.scenario == Scenario.INTERNAL_ERROR:
        return error_response(500, "ALGORITHM_INTERNAL_ERROR", "Mock internal error", request.requestId)
    if request.scenario == Scenario.PARTIAL_REJECTION and (not request.vehicles or len(request.orders) < 2):
        return error_response(
            400,
            "INVALID_INPUT",
            "PARTIAL_REJECTION requires at least one vehicle and two orders",
            request.requestId,
        )

    job_id = str(uuid4())
    accepted_at = now()
    jobs[job_id] = JobRecord(
        request=request,
        jobId=job_id,
        status=PlanningStatus.ACCEPTED,
        acceptedAt=accepted_at,
    )
    return PlanningJobAcceptedResponse(
        jobId=job_id,
        requestId=request.requestId,
        snapshotId=request.snapshotId,
        status=PlanningStatus.ACCEPTED,
        acceptedAt=accepted_at,
    )


def get_record(job_id: str) -> JobRecord:
    record = jobs.get(job_id)
    if record is None:
        raise HTTPException(status_code=404, detail={"code": "JOB_NOT_FOUND", "message": "Planning job not found"})
    return record


@app.get(
    "/api/algorithm/v1/planning-jobs/{job_id}",
    response_model=PlanningJobStatusResponse,
    responses={404: {"model": ErrorResponse}},
)
def get_planning_job_status(job_id: str) -> PlanningJobStatusResponse:
    record = get_record(job_id)
    advance(record)
    error = None
    if record.status == PlanningStatus.NO_FEASIBLE_SOLUTION:
        error = ErrorResponse(
            code="NO_FEASIBLE_SOLUTION",
            message="No feasible plan for the supplied snapshot",
            requestId=record.request.requestId,
        )
    elif record.status == PlanningStatus.TIMEOUT:
        error = ErrorResponse(code="TIMEOUT", message="Mock planning timed out", requestId=record.request.requestId)

    return PlanningJobStatusResponse(
        jobId=job_id,
        requestId=record.request.requestId,
        snapshotId=record.request.snapshotId,
        status=record.status,
        algorithmVersion=ALGORITHM_VERSION,
        parameterVersion=record.request.algorithmConfig.parameterVersion,
        updatedAt=now(),
        error=error,
    )


def build_result(record: JobRecord) -> PlanningJobResultResponse:
    request = record.request
    vehicles = [vehicle for vehicle in request.vehicles if vehicle.status.upper() in {"AVAILABLE", "IDLE", "IN_SERVICE"}]
    rejected: list[RejectedOrder] = []
    assigned_orders = list(request.orders)

    if request.scenario == Scenario.PARTIAL_REJECTION:
        rejected_order = assigned_orders.pop()
        rejected.append(
            RejectedOrder(
                orderId=rejected_order.orderId,
                reasonCode="MOCK_PARTIAL_REJECTION",
                reasonMessage="Order rejected by the selected Mock scenario",
            )
        )

    if not vehicles:
        rejected.extend(
            RejectedOrder(orderId=order.orderId, reasonCode="NO_AVAILABLE_VEHICLE", reasonMessage="No available vehicle")
            for order in assigned_orders
        )
        assigned_orders = []

    assignments: dict[int, list[PlanningOrder]] = {vehicle.vehicleId: [] for vehicle in vehicles}
    for index, order in enumerate(assigned_orders):
        vehicle = vehicles[index % len(vehicles)]
        assignments[vehicle.vehicleId].append(order)

    vehicle_plans: list[VehiclePlan] = []
    for vehicle in vehicles:
        orders = assignments[vehicle.vehicleId]
        route_nodes = [
            RouteNode(
                nodeId=vehicle.roadNodeId or f"vehicle-{vehicle.vehicleId}-start",
                action="START",
                longitude=vehicle.longitude,
                latitude=vehicle.latitude,
                estimatedArrivalTime=request.planningTime,
            )
        ]
        for index, order in enumerate(orders, start=1):
            route_nodes.extend(
                [
                    RouteNode(
                        nodeId=order.pickupNodeId,
                        orderId=order.orderId,
                        action="PICKUP",
                        longitude=vehicle.longitude,
                        latitude=vehicle.latitude,
                        estimatedArrivalTime=request.planningTime + timedelta(minutes=index * 10),
                    ),
                    RouteNode(
                        nodeId=order.deliveryNodeId,
                        orderId=order.orderId,
                        action="DROPOFF",
                        longitude=vehicle.longitude,
                        latitude=vehicle.latitude,
                        estimatedArrivalTime=request.planningTime + timedelta(minutes=index * 20),
                    ),
                ]
            )
        vehicle_plans.append(
            VehiclePlan(
                vehicleId=vehicle.vehicleId,
                shiftId=vehicle.shiftId,
                assignedOrderIds=[order.orderId for order in orders],
                routeNodes=route_nodes,
            )
        )

    return PlanningJobResultResponse(
        jobId=record.jobId,
        requestId=request.requestId,
        snapshotId=request.snapshotId,
        algorithmVersion=ALGORITHM_VERSION,
        parameterVersion=request.algorithmConfig.parameterVersion,
        status=record.status,
        score=max(0.0, 100.0 - len(rejected) * 10.0),
        vehiclePlans=vehicle_plans,
        rejectedOrders=rejected,
        metrics=Metrics(
            extraDistanceKm=float(len(assigned_orders) * 1.5),
            estimatedPassengerDelayMinutes=0.0,
            estimatedRevenue=float(len(assigned_orders) * 20),
            estimatedCost=float(len(vehicle_plans) * 8),
        ),
    )


@app.get(
    "/api/algorithm/v1/planning-jobs/{job_id}/result",
    response_model=PlanningJobResultResponse,
    responses={404: {"model": ErrorResponse}, 409: {"model": ErrorResponse}, 500: {"model": ErrorResponse}},
)
def get_planning_job_result(job_id: str):
    record = get_record(job_id)
    if record.status in {PlanningStatus.ACCEPTED, PlanningStatus.RUNNING}:
        advance(record)
    if record.status in {PlanningStatus.ACCEPTED, PlanningStatus.RUNNING}:
        return error_response(409, "JOB_NOT_READY", "Planning job is not complete", record.request.requestId)
    if record.status == PlanningStatus.TIMEOUT:
        return error_response(409, "TIMEOUT", "Mock planning timed out", record.request.requestId)
    if record.status == PlanningStatus.NO_FEASIBLE_SOLUTION:
        return PlanningJobResultResponse(
            jobId=record.jobId,
            requestId=record.request.requestId,
            snapshotId=record.request.snapshotId,
            algorithmVersion=ALGORITHM_VERSION,
            parameterVersion=record.request.algorithmConfig.parameterVersion,
            status=record.status,
            score=0,
            vehiclePlans=[],
            rejectedOrders=[
                RejectedOrder(
                    orderId=order.orderId,
                    reasonCode="NO_FEASIBLE_SOLUTION",
                    reasonMessage="No feasible plan for the supplied snapshot",
                )
                for order in record.request.orders
            ],
            metrics=Metrics(
                extraDistanceKm=0,
                estimatedPassengerDelayMinutes=0,
                estimatedRevenue=0,
                estimatedCost=0,
            ),
        )
    return build_result(record)
