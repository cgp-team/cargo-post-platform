"""HACO-CPS 1.4.1 Hard Deadline：统一时间预算对象。

所有搜索组件共享同一个 SearchDeadline 实例，不再各自计算
``time.monotonic() + xxx``。到期后立即停止产生新解，保留最后一个
完整、经过 FeasibilityEngine 验证的 best_routes。
"""

from __future__ import annotations

import time
from dataclasses import dataclass


@dataclass(frozen=True)
class SearchDeadline:
    """不可变时间预算；创建后不可延长。"""

    deadline: float

    @classmethod
    def from_seconds(cls, seconds: float) -> SearchDeadline:
        return cls(time.monotonic() + max(0.0, float(seconds)))

    def expired(self) -> bool:
        return time.monotonic() >= self.deadline

    def remaining(self) -> float:
        return max(0.0, self.deadline - time.monotonic())

    def check(self) -> bool:
        """未过期返回 True；与 ``if deadline.expired(): break`` 互补。"""
        return not self.expired()
