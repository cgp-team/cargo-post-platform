"""HACO-CPS 1.4.1 Hard Deadline：统一时间预算对象。

所有搜索组件共享同一个 SearchDeadline 实例，不再各自计算
``time.monotonic() + xxx``。到期后立即停止产生新解，保留最后一个
完整、经过 FeasibilityEngine 验证的 best_routes。

评估次数预算（可选）：与时间预算并列的确定性上限。CI/批量回归用
``max_evaluations`` 可消除 wall-clock 抖动；生产默认 None（只看时间）。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field


@dataclass
class SearchDeadline:
    """时间预算 + 可选评估次数预算；创建后时间不可延长。"""

    deadline: float
    max_evaluations: int | None = None
    evaluations: int = field(default=0, compare=False)

    @classmethod
    def from_seconds(
        cls,
        seconds: float,
        *,
        max_evaluations: int | None = None,
    ) -> "SearchDeadline":
        return cls(
            deadline=time.monotonic() + max(0.0, float(seconds)),
            max_evaluations=max_evaluations,
        )

    def expired(self) -> bool:
        if time.monotonic() >= self.deadline:
            return True
        return (
            self.max_evaluations is not None
            and self.evaluations >= self.max_evaluations
        )

    def remaining(self) -> float:
        return max(0.0, self.deadline - time.monotonic())

    def check(self) -> bool:
        """未过期返回 True；与 ``if deadline.expired(): break`` 互补。"""
        return not self.expired()

    def tick(self, n: int = 1) -> bool:
        """记 n 次评估；返回是否仍可继续搜索。"""
        self.evaluations += n
        return not self.expired()

    @property
    def budget_exhausted_by_count(self) -> bool:
        return (
            self.max_evaluations is not None
            and self.evaluations >= self.max_evaluations
        )
