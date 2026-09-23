"""一站式订单预筛选：可达性 + 绕行 + SLA + 运力 + 联运建议 + 生命周期落点。"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Sequence

from .failure_cases import classify_reason
from .models import (
    MarginalCostBreakdown,
    ReachabilityStatus,
)
from .order_lifecycle import OrderLifecycle, OrderState
from .reachability import AccessFlags, LocationSnapshot, classify_reachability
from .sla_commit import SlaDecision, SlaLevel, evaluate_sla
from .transfer_graph import LineSpec, TransferPath, suggest_multileg

# 真实山区/城区公交客货邮参考运价（重庆区县口径，可被业务覆盖）
DEFAULT_YUAN_PER_KM = 2.8
DEFAULT_YUAN_PER_MIN = 0.45
DEFAULT_HANDOVER_FEE = 8.0


class PrescreenDecision(str, Enum):
    ACCEPT = "ACCEPT"                 # 进订单池
    TRANSFER_MULTILEG = "TRANSFER_MULTILEG"
    REJECT = "REJECT"
    DEFER = "DEFER"


@dataclass
class PrescreenOrderInput:
    order_id: str
    kind: str  # PASSENGER / DELIVERY / PICKUP / SHIPMENT
    origin: str
    dest: str
    quantity: int = 1
    weight_kg: float = 0.0
    volume_m3: float = 0.0
    economic_value: float | None = None
    deadline_s: float | None = None
    now_s: float = 0.0
    # 可达性注入
    access: AccessFlags = field(default_factory=AccessFlags)
    location: LocationSnapshot | None = None
    # 运力
    vehicle_cargo_slots: int = 12
    vehicle_weight_cap_kg: float = 350.0
    vehicle_volume_cap_m3: float = 2.5
    current_peak_slots: int = 0
    current_peak_kg: float = 0.0
    current_peak_m3: float = 0.0
    # 绕行
    detour_m: float | None = None
    detour_duration_s: float | None = None
    max_detour_m: float = 3000.0
    passenger_count_onboard: int = 0
    # 网络
    lines: Sequence[LineSpec] = ()
    note: str = ""


@dataclass
class PrescreenResult:
    order_id: str
    decision: PrescreenDecision
    state: OrderState
    reason_code: str
    category: str
    explanation: str
    sla: SlaDecision | None = None
    multileg: TransferPath | None = None
    detour_m: float | None = None
    incremental_cost: float | None = None
    lifecycle: OrderLifecycle | None = None


def estimate_incremental_cost(
    *,
    detour_m: float,
    detour_duration_s: float,
    passenger_impact_s: float = 0.0,
    handover_count: int = 0,
    yuan_per_km: float = DEFAULT_YUAN_PER_KM,
    yuan_per_min: float = DEFAULT_YUAN_PER_MIN,
    handover_fee: float = DEFAULT_HANDOVER_FEE,
) -> float:
    """真实量纲计价（元）：里程 + 时间 + 乘客影响 + 交接费。"""
    cost = (max(0.0, detour_m) / 1000.0) * yuan_per_km
    cost += (max(0.0, detour_duration_s) / 60.0) * yuan_per_min
    cost += (max(0.0, passenger_impact_s) / 60.0) * yuan_per_min * 0.8
    cost += max(0, handover_count) * handover_fee
    return round(cost, 2)


def prescreen_order(inp: PrescreenOrderInput) -> PrescreenResult:
    """一站式预筛：可达性 → 运力峰值 → 绕行成本 → SLA → 联运建议。"""
    life = OrderLifecycle(inp.order_id)

    def _done(
        decision: PrescreenDecision,
        state: OrderState,
        reason: str,
        explanation: str,
        **kw,
    ) -> PrescreenResult:
        life.transition(state, reason=reason, detail={"explanation": explanation})
        return PrescreenResult(
            order_id=inp.order_id,
            decision=decision,
            state=state,
            reason_code=reason,
            category=classify_reason(reason).value,
            explanation=explanation,
            lifecycle=life,
            detour_m=inp.detour_m,
            **kw,
        )

    # 1) 可达性
    reach = classify_reachability(
        flags=inp.access,
        location=inp.location,
        service_point=inp.dest,
        original_point=inp.dest,
        route_distance_m=inp.detour_m or 0.0,
        route_duration_s=inp.detour_duration_s or 0.0,
    )
    if not reach.reachable and reach.status != ReachabilityStatus.UNKNOWN:
        ml = suggest_multileg(inp.lines, service_near_map().get(inp.origin, inp.origin), service_near_map().get(inp.dest, inp.dest)) if inp.lines else None
        if reach.reason_code.value in ("ROAD_UNREACHABLE", "ALREADY_PASSED") and ml is not None:
            return _done(
                PrescreenDecision.TRANSFER_MULTILEG, OrderState.TRANSFER,
                reach.reason_code.value,
                reach.explanation + f" 已搜换乘图：{'→'.join(ml.line_chain)}（{ml.transfers} 次交接）。",
                multileg=ml,
            )
        return _done(
            PrescreenDecision.REJECT, OrderState.REJECTED,
            reach.reason_code.value, reach.explanation,
        )

    # 2) 运力峰值（叠加后仍不超）
    if inp.current_peak_slots + inp.quantity > inp.vehicle_cargo_slots:
        return _done(
            PrescreenDecision.REJECT, OrderState.REJECTED,
            "CARGO_CAPACITY_EXCEEDED",
            f"峰值货位 {inp.current_peak_slots}+{inp.quantity} > {inp.vehicle_cargo_slots}，拒入池。",
        )
    if inp.current_peak_kg + inp.weight_kg > inp.vehicle_weight_cap_kg + 1e-6:
        return _done(
            PrescreenDecision.REJECT, OrderState.REJECTED,
            "CARGO_WEIGHT_CAPACITY_EXCEEDED",
            f"峰值重量 {inp.current_peak_kg + inp.weight_kg:.0f}kg 超载重 {inp.vehicle_weight_cap_kg:.0f}kg。",
        )
    if inp.current_peak_m3 + inp.volume_m3 > inp.vehicle_volume_cap_m3 + 1e-6:
        return _done(
            PrescreenDecision.REJECT, OrderState.REJECTED,
            "CARGO_VOLUME_CAPACITY_EXCEEDED",
            f"峰值体积 {inp.current_peak_m3 + inp.volume_m3:.2f}m³ 超容积 {inp.vehicle_volume_cap_m3:.2f}m³。",
        )

    # 3) 绕行成本
    detour_m = inp.detour_m or 0.0
    detour_s = inp.detour_duration_s or (detour_m / 250.0 * 60.0)  # 约 25km/h
    pax_impact = detour_s * max(0, inp.passenger_count_onboard)
    cost = estimate_incremental_cost(
        detour_m=detour_m, detour_duration_s=detour_s, passenger_impact_s=pax_impact,
    )
    if detour_m > inp.max_detour_m:
        ml = suggest_multileg(inp.lines, service_near_map().get(inp.origin, inp.origin), service_near_map().get(inp.dest, inp.dest)) if inp.lines else None
        exp = (
            f"绕行 {detour_m/1000:.2f}km 超单次预算 {inp.max_detour_m/1000:.1f}km"
            f"（增量成本约 ¥{cost}，乘客影响 {pax_impact:.0f}s）。"
        )
        if ml is not None:
            return _done(
                PrescreenDecision.TRANSFER_MULTILEG, OrderState.TRANSFER,
                "DETOUR_BUDGET_EXCEEDED",
                exp + f" 建议联运：{'→'.join(ml.line_chain)}。",
                multileg=ml, incremental_cost=cost,
            )
        return _done(
            PrescreenDecision.DEFER, OrderState.DEFERRED,
            "DETOUR_BUDGET_EXCEEDED", exp + " 建议下一班/他线。",
            incremental_cost=cost,
        )

    # 4) SLA
    sla = evaluate_sla(
        estimated_delivery_s=inp.now_s + detour_s + 600.0,
        deadline_s=inp.deadline_s,
        now_s=inp.now_s,
    )
    if sla.level is SlaLevel.REJECT:
        return _done(
            PrescreenDecision.REJECT, OrderState.REJECTED,
            sla.reason_code, sla.explanation, sla=sla, incremental_cost=cost,
        )

    # 5) 直达不可用时才查联运（服务点映射最近骨架站）
    _near = service_near_map()
    if not _direct_line_covers(inp.lines, _near.get(inp.origin, inp.origin), _near.get(inp.dest, inp.dest)):
        ml = suggest_multileg(inp.lines, service_near_map().get(inp.origin, inp.origin), service_near_map().get(inp.dest, inp.dest)) if inp.lines else None
        if ml is None:
            return _done(
                PrescreenDecision.DEFER, OrderState.DEFERRED,
                "CANDIDATE_POOL_MISS",
                "单线无法直达且换乘图无可行 1–3 腿路径；延后人工调度/专送/同城落地配。",
            )
        return _done(
            PrescreenDecision.TRANSFER_MULTILEG, OrderState.TRANSFER,
            "MULTILEG_GRAPH_OK",
            f"换乘图最优 {'→'.join(ml.line_chain)}，交接 {ml.transfers} 次，经停 {ml.stops_total} 站。"
            + (f" {inp.note}" if inp.note else ""),
            multileg=ml, sla=sla, incremental_cost=cost,
        )

    level_note = "并需监控 ETA。" if sla.level is SlaLevel.CRITICAL else "可承诺。"
    return _done(
        PrescreenDecision.ACCEPT, OrderState.POOL,
        "PRESCREEN_PASS",
        f"入池：绕行 {detour_m/1000:.2f}km，增量成本约 ¥{cost}"
        f"{'，时效临界' if sla.level is SlaLevel.CRITICAL else ''}，{level_note}"
        + (f" {inp.note}" if inp.note else ""),
        sla=sla, incremental_cost=cost,
    )


def _direct_line_covers(lines: Sequence[LineSpec], o: str, d: str) -> bool:
    """公交线路去/返程双向可承运；同站（本地服务）也算覆盖。"""
    for ln in lines:
        if o not in ln.stations or d not in ln.stations:
            continue
        if o == d:
            return True  # 站点/服务点本地揽派
        io, id_ = ln.stations.index(o), ln.stations.index(d)
        # 去程 io→id 或 返程 id→io 均可（农村客货邮对开）
        return True
    return False


def service_near_map() -> dict[str, str]:
    """服务点/枢纽 → 最近骨架站（预筛直达覆盖判定）。"""
    return {
        "F-KDG": "CYU", "F-NCM": "HJY", "F-YSP": "NSZW",
        "F-NPSP": "NPZX", "F-XHL-YL": "XHL", "F-TZS-SL": "SXS",
        "F-GYQ-BW": "GYQ", "F-SPB-WL": "SPB", "F-CTM-MY": "CTM",
        "F-DLX-KDG": "DLX", "CQXZ": "SPB", "TZS": "SXS",
    }
