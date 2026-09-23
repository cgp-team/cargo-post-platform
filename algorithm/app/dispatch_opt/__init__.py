"""动态调度算法层：公交主任务骨架 + 货运机会窗口 + 边际成本 + 候选选择。

本包是**算法内部决策层**，不新建业务表，不替代 DispatchPlan / MultiLeg / Shift。
职责边界：
- HACO/ALNS：复杂组合搜索（保留）
- 本包：新订单分级响应、Gap 绕行、发车锁定、候选比较、不可达恢复
- FeasibilityEngine：最终硬约束裁判
- DispatchPlan / Driver：执行层
"""

from .models import (
    CargoOpportunitySlot,
    HandoverCostBreakdown,
    HandoverKind,
    HandoverDecision,
    MarginalCostBreakdown,
    ReachabilityDecision,
    ReachabilityReason,
    ReachabilityStatus,
    RecoveryAction,
    TripCandidate,
    TripExecutionState,
)
from .gap_detour import calculate_gap_detour, can_detour_within_gap
from .marginal_cost import CostModel, DefaultCostModel, MarginalCostEvaluator
from .trip_lock import HighValueRealtimeInsertPolicy, TripLockPolicy
from .candidate_selector import RemainingDispatchAllocator, TripCandidateSelector
from .handover import TransportChainEvaluator, can_handover
from .segment_completion import TaskSegmentCompletionValidator
from .reachability import classify_reachability, plan_recovery
from .remaining_replan import RemainingSegmentReplan
from .comparator import compare_trip_candidates
from .explanation import explain_choice, explain_unreachable_recovery
from .allocate import AllocationRequest, AllocationResult, allocate_new_order
from .route_cost_provider import (
    CachedRealRouteProvider,
    GapRoutePair,
    HaversineLowerBoundProvider,
    MapEngineRouteCostProvider,
    RouteCost,
    RouteCostProvider,
    RouteCostStatus,
    build_gap_waypoints,
    default_route_cost_provider,
)
from .candidate_builder import (
    CandidateType,
    DispatchCandidate,
    DispatchCandidateBuilder,
    DispatchOrder,
    TripView,
)
from .economic_policy import (
    EconomicAdmission,
    EconomicDecision,
    EconomicPolicy,
    EconomicPolicyInput,
)
from .flexibility import (
    FlexibilityInput,
    FlexibilityLevel,
    OrderFlexibility,
    dispatch_priority,
    estimate_flexibility,
)
from .decision_trace import DecisionTrace, DecisionTraceRecord, summarize_traces
from .pool_diagnostics import PoolDiagnostics, diagnose_candidate_pool
from .coordinator import DispatchPlan, DispatchRequest, DynamicDispatchCoordinator
from .reachability import (
    CandidateReachability,
    CandidateReachabilityInput,
    classify_candidate_reachability,
)
from .runtime import (
    DispatchAllocatePayload,
    DispatchOrderPayload,
    DispatchTripPayload,
    allocate_dispatch,
    build_dispatch_request,
    get_route_cost_provider,
)

__all__ = [
    "CargoOpportunitySlot",
    "HandoverCostBreakdown",
    "HandoverDecision",
    "HandoverKind",
    "MarginalCostBreakdown",
    "ReachabilityDecision",
    "ReachabilityReason",
    "ReachabilityStatus",
    "RecoveryAction",
    "TripCandidate",
    "TripExecutionState",
    "calculate_gap_detour",
    "can_detour_within_gap",
    "CostModel",
    "DefaultCostModel",
    "MarginalCostEvaluator",
    "TripLockPolicy",
    "HighValueRealtimeInsertPolicy",
    "TripCandidateSelector",
    "RemainingDispatchAllocator",
    "TransportChainEvaluator",
    "can_handover",
    "TaskSegmentCompletionValidator",
    "classify_reachability",
    "plan_recovery",
    "RemainingSegmentReplan",
    "compare_trip_candidates",
    "explain_choice",
    "RouteCost",
    "RouteCostStatus",
    "RouteCostProvider",
    "GapRoutePair",
    "HaversineLowerBoundProvider",
    "MapEngineRouteCostProvider",
    "CachedRealRouteProvider",
    "build_gap_waypoints",
    "default_route_cost_provider",
    "CandidateType",
    "DispatchCandidate",
    "DispatchCandidateBuilder",
    "DispatchOrder",
    "TripView",
    "EconomicAdmission",
    "EconomicDecision",
    "EconomicPolicy",
    "EconomicPolicyInput",
    "FlexibilityInput",
    "FlexibilityLevel",
    "OrderFlexibility",
    "dispatch_priority",
    "estimate_flexibility",
    "DecisionTrace",
    "DecisionTraceRecord",
    "summarize_traces",
    "PoolDiagnostics",
    "diagnose_candidate_pool",
    "DispatchPlan",
    "DispatchRequest",
    "DynamicDispatchCoordinator",
    "CandidateReachability",
    "CandidateReachabilityInput",
    "classify_candidate_reachability",
    "DispatchAllocatePayload",
    "DispatchOrderPayload",
    "DispatchTripPayload",
    "allocate_dispatch",
    "build_dispatch_request",
    "get_route_cost_provider",
]
