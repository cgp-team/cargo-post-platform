"""AMap 配额与分层抽样：训练主循环不打高德。"""

from __future__ import annotations

import time
from dataclasses import dataclass, field


@dataclass
class AMapQuotaScheduler:
    daily_budget: int = 5000
    reserve: int = 500
    target_use: int = 4000
    used: int = 0
    day: str = field(default_factory=lambda: time.strftime("%Y-%m-%d"))

    def _roll(self) -> None:
        today = time.strftime("%Y-%m-%d")
        if today != self.day:
            self.day = today
            self.used = 0

    @property
    def remaining(self) -> int:
        self._roll()
        return max(0, self.daily_budget - self.used)

    @property
    def low_priority_allowed(self) -> bool:
        return self.remaining > self.reserve

    def try_consume(self, priority: str = "low") -> bool:
        self._roll()
        if self.used >= self.daily_budget:
            return False
        if priority != "high" and not self.low_priority_allowed:
            return False
        if self.used >= self.target_use and priority != "high":
            return False
        self.used += 1
        return True

    def snapshot(self) -> dict:
        self._roll()
        return {
            "amap_used_today": self.used,
            "amap_remaining_today": self.remaining,
            "daily_budget": self.daily_budget,
            "reserve": self.reserve,
        }


class AMapValidationSampler:
    """优先级抽样，禁止简单 random。"""

    PRIORITY = (
        "new_road_pattern", "new_station", "new_route", "hard_negative",
        "model_disagreement", "gh_low_confidence", "top_candidate",
        "baseline_top_candidate", "multi_leg", "high_value",
    )

    def select(self, candidates: list[dict], limit: int) -> list[dict]:
        def prio(c: dict) -> int:
            for i, name in enumerate(self.PRIORITY):
                if c.get(name):
                    return i
            return len(self.PRIORITY)

        return sorted(candidates, key=prio)[:limit]
