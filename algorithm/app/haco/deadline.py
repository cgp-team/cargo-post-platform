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


class PhaseBudget:
    """同一搜索内的阶段配额：共享主 deadline 的硬截止与评估计数，只允许更早收手。

    为什么单独造这个类型：各阶段若各拿一个独立 SearchDeadline，评估计数就各自
    归零，「第 55% 预算」这种说法在计数口径下根本不成立；而主链的 anytime 判据
    必须建立在「全局一共做了多少次完整评估」上，才不受机器负载影响
    （墙钟预算下同一实例同一种子实测能差 44%）。

    鸭子类型：使用方只调 expired() / tick() / remaining() / check()。
    """

    def __init__(self, master: SearchDeadline, frac: float,
                 anchor: float, total_seconds: float):
        self.master = master
        self.frac = max(0.0, min(1.0, float(frac)))
        self.anchor = anchor
        self.total_seconds = total_seconds

    def expired(self) -> bool:
        if self.master.expired():
            return True
        if self.frac >= 1.0:
            return False
        lim = self.master.max_evaluations
        if lim and self.master.evaluations >= max(1, int(lim * self.frac)):
            return True
        if self.total_seconds > 0 and (time.monotonic() - self.anchor) >= self.total_seconds * self.frac:
            return True
        return False

    def check(self) -> bool:
        return not self.expired()

    def tick(self, n: int = 1) -> bool:
        return self.master.tick(n)

    def remaining(self) -> float:
        """时间余量（秒）。计数预算下这只作上界用：真正的确定性判据是 expired()。"""
        left = self.master.remaining()
        if self.total_seconds > 0:
            left = min(
                left,
                max(0.0, self.total_seconds * self.frac - (time.monotonic() - self.anchor)),
            )
        return max(0.0, left)

    @property
    def evaluations(self) -> int:
        return self.master.evaluations
