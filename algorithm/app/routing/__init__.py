"""真实路网—算法—调度 Geometry 闭环中间层。

LocalRoutingEngine 在真实道路 Graph 上路由（禁止 Haversine 正式结果）。
AMapRoutingEngine 做最终校验与权威 Geometry。
GeometryCache 保证同请求稳定、降低高德调用。
"""

from .models import (
    FORMAL_GEOMETRY_STATUSES,
    GeometrySource,
    GeometryStatus,
    RouteGeometryResult,
    RouteType,
    estimated_only,
    is_formal_geometry,
    uncertain,
)
from .geometry_cache import GeometryCache, RoutingCallBudget, route_fingerprint
from .local_routing import (
    LocalRoutingEngine,
    RoadEdge,
    RoadGraph,
    haversine_m,
    straight_lower_bound_m,
)
from .engine import (
    AMapRoutingEngine,
    AMapRoutingPort,
    GeometrySanityValidator,
    MapRoutingEngine,
)
from .gap_routing import FlexibleGapRoute, gap_candidates, plan_gap_routes
from .real_road_cost import RealRoadMarginalCostEvaluator, RoadMarginalCost

__all__ = [
    "FORMAL_GEOMETRY_STATUSES",
    "GeometrySource",
    "GeometryStatus",
    "RouteGeometryResult",
    "RouteType",
    "estimated_only",
    "is_formal_geometry",
    "uncertain",
    "GeometryCache",
    "RoutingCallBudget",
    "route_fingerprint",
    "LocalRoutingEngine",
    "RoadEdge",
    "RoadGraph",
    "haversine_m",
    "straight_lower_bound_m",
    "AMapRoutingEngine",
    "AMapRoutingPort",
    "GeometrySanityValidator",
    "MapRoutingEngine",
    "FlexibleGapRoute",
    "gap_candidates",
    "plan_gap_routes",
    "RealRoadMarginalCostEvaluator",
    "RoadMarginalCost",
]
