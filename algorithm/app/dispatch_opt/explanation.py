"""方案解释：必须来自真实计算结果，禁止写死演示文案。"""

from __future__ import annotations

from .models import TripCandidate


def explain_choice(chosen: TripCandidate, alternatives: list[TripCandidate] | None = None) -> str:
    parts: list[str] = []
    if chosen.source_kind == "CURRENT_TRIP":
        parts.append(
            f"当前 {chosen.route_id} 路班次 {chosen.shift_id} 可承运"
        )
        if chosen.detour_distance_m > 0:
            parts.append(f"局部绕行 {chosen.detour_distance_m:.0f}m")
        parts.append(f"乘客影响 {chosen.passenger_impact_s:.0f}s")
    elif chosen.source_kind in ("NEXT_TRIP", "LATER_TRIP"):
        parts.append(
            f"当前班次不建议追加；等待 {chosen.waiting_time_s/60:.0f} 分钟由"
            f"{chosen.route_id} {chosen.shift_id} 承运"
        )
    elif chosen.source_kind == "OTHER_ROUTE":
        parts.append(
            f"改由其他线路 {chosen.route_id} {chosen.shift_id} 承运，等待 "
            f"{chosen.waiting_time_s/60:.0f} 分钟"
        )
    elif chosen.source_kind == "MULTI_LEG":
        parts.append(
            f"MultiLeg 联运 {' → '.join(chosen.legs) if chosen.legs else chosen.route_id}"
            f"，交接 {chosen.handover_count} 次"
        )
    else:
        parts.append(f"选择 {chosen.source_kind} {chosen.route_id}")

    if chosen.incremental_cost:
        parts.append(f"增量成本 {chosen.incremental_cost:.2f}")
    if chosen.efficiency is not None:
        parts.append(f"性价比 {chosen.efficiency:.2f}")

    # 与更差候选对比，写出真实差值
    if alternatives:
        worse = [c for c in alternatives if c.feasibility and c is not chosen]
        if worse:
            alt = min(worse, key=lambda c: (c.incremental_cost, c.passenger_impact_s))
            if alt.detour_distance_m > chosen.detour_distance_m:
                parts.append(
                    f"较 {alt.source_kind} 方案减少绕行 "
                    f"{alt.detour_distance_m - chosen.detour_distance_m:.0f}m"
                )
            if alt.passenger_impact_s > chosen.passenger_impact_s:
                parts.append(
                    f"降低乘客扰动 {alt.passenger_impact_s - chosen.passenger_impact_s:.0f}s"
                )

    return "，".join(parts) + "。"


def explain_unreachable_recovery(
    reason_code: str,
    chosen: TripCandidate | None,
    original_point: str | None = None,
    service_point: str | None = None,
) -> str:
    if chosen is None:
        return (
            f"当前不可达原因 {reason_code}；暂无自动恢复方案，进入人工处理/等待信息刷新。"
        )
    if service_point and original_point and service_point != original_point:
        return (
            f"用户原始位置 {original_point} 车辆不可直接服务，切换至合法服务点 {service_point}；"
            f"随后 {explain_choice(chosen)}"
        )
    return f"不可达原因 {reason_code}；{explain_choice(chosen)}"
