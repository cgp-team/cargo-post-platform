"""结构化候选比较器：硬可行 → 乘客服务 → 稳定性 → 经济 → 成本。

禁止用无法解释的大权重冒充业务最优。
"""

from __future__ import annotations

from typing import Sequence

from .models import TripCandidate


def compare_trip_candidates(candidates: Sequence[TripCandidate]) -> list[TripCandidate]:
    """返回排序后的候选（优→劣）。仅比较 feasibility=True 的排在前面。"""

    def key(c: TripCandidate):
        eff = c.efficiency if c.efficiency is not None else float("-inf")
        # 第一优先 Hard Feasibility
        # 第二优先 Passenger Service
        # 第三优先 Trip Stability（handover 少、扰动小）
        # 第四优先 Economic Efficiency（越大越好 → 取负）
        # 第五优先 Operational Cost
        # 第六优先 Distance/Duration
        return (
            0 if c.feasibility else 1,
            c.passenger_impact_s,
            c.handover_count,
            c.detour_distance_m,
            c.waiting_time_s,
            -eff,
            c.incremental_cost,
            c.detour_duration_s,
            c.reason_code,
        )

    return sorted(candidates, key=key)
