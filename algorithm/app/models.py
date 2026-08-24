"""契约数据模型，与 docs/api/algorithm-api.yaml 对齐。

业务侧省略 null 字段，所有可选字段缺失一律按"没有"处理，不得报参数错误。
"""

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Scenario(str, Enum):
    """Mock 混沌测试字段；真实算法接受但忽略（契约标注"真实算法可忽略"）。"""

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
    """ACO 超参数为算法组方案的历史契约字段；本服务求解器为 OR-Tools，

    参数全部接受但不参与求解，超出建议范围时响应带 warnings 不拒绝（契约 Q8）。
    """

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
    scenario: Scenario | None = None

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
    # 分段路网行驶秒数（仅高德矩阵路径；欧氏路径为 None，后端按直线÷均速兜底）
    segmentDuration: float | None = None


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
    # 距离单位：degree=欧氏直线（度，后端按 Haversine 换算）；km=路网真实公里（后端直通）
    distanceUnit: str = "degree"
    totalDistance: float = 0.0
    vehiclePlans: list[VehiclePlan] = Field(default_factory=list)
    computedAt: datetime


class ErrorResponse(BaseModel):
    code: str
    message: str
    requestId: str | None = None
    details: dict[str, Any] | None = None


class DistancePoint(BaseModel):
    stationId: str
    longitude: float
    latitude: float


class DistanceRequest(BaseModel):
    """两站点间距离查询（寄货页取货→送达站点路网距离/耗时）。"""

    requestId: str
    stations: list[DistancePoint]


class DistancePair(BaseModel):
    fromStationId: str
    toStationId: str
    # 里程（恒为公里 km，不做 degree 换算）
    distanceKm: float | None = None
    # 行驶秒数（高德路网真实秒；euclidean 直线估算也给出按均速估算的秒）
    durationSeconds: float | None = None
    # 数据来源：amap=高德路网 / euclidean=直线估算
    provider: str = "amap"
    # 是否可用：False=该点对明确不可达（无距离/时长）
    available: bool = True


class DistanceResponse(BaseModel):
    requestId: str
    # 恒为 "km"（高德路网公里 / 直线估算 Haversine 公里，均不重复换算）
    distanceUnit: str = "km"
    pairs: list[DistancePair] = Field(default_factory=list)
    computedAt: datetime


class RoutePoint(BaseModel):
    """坐标点（GCJ-02），范围校验防 malformed 输入。"""
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)


class RouteRequest(BaseModel):
    """单路线查询：坐标 → 坐标（实时公交 ETA 用：车辆位置 → 下一站）。"""
    origin: RoutePoint
    destination: RoutePoint


class RouteResponse(BaseModel):
    available: bool
    # 距离（恒为公里 km）
    distanceKm: float | None = None
    # 行驶秒数（高德真实秒；euclidean 直线估算也给出按均速换算的秒）
    durationSeconds: float | None = None
    # 数据来源：amap=高德路网 / euclidean=直线估算
    provider: str = "amap"
    # 不可用时原因码（如 ROUTE_UNAVAILABLE）
    reasonCode: str | None = None
