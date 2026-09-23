"""订单生命周期状态机（预筛 → 入池 → 调度 → 终态）。"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class OrderState(str, Enum):
    PENDING_PRESCREEN = "PENDING_PRESCREEN"
    POOL = "POOL"                    # 预筛通过，待调度
    ASSIGNED = "ASSIGNED"            # 已上车/已生成停靠
    REJECTED = "REJECTED"            # 预筛或调度拒绝（终态）
    DEFERRED = "DEFERRED"            # 延后/下一班/他线
    TRANSFER = "TRANSFER"            # 联运改派（可再进池）


_ALLOWED: dict[OrderState, set[OrderState]] = {
    OrderState.PENDING_PRESCREEN: {
        OrderState.POOL, OrderState.REJECTED, OrderState.DEFERRED, OrderState.TRANSFER,
    },
    OrderState.POOL: {
        OrderState.ASSIGNED, OrderState.REJECTED, OrderState.DEFERRED, OrderState.TRANSFER,
    },
    OrderState.DEFERRED: {
        OrderState.POOL, OrderState.REJECTED, OrderState.TRANSFER, OrderState.ASSIGNED,
    },
    OrderState.TRANSFER: {
        OrderState.POOL, OrderState.ASSIGNED, OrderState.REJECTED,
    },
    OrderState.ASSIGNED: set(),
    OrderState.REJECTED: set(),
}


class IllegalTransition(ValueError):
    pass


@dataclass
class OrderLifecycle:
    order_id: str
    state: OrderState = OrderState.PENDING_PRESCREEN
    history: list[dict[str, Any]] = field(default_factory=list)

    def transition(self, to: OrderState, *, reason: str = "", detail: dict | None = None) -> OrderState:
        if to == self.state:
            return self.state
        if to not in _ALLOWED[self.state]:
            raise IllegalTransition(f"{self.order_id}: {self.state.value} → {to.value} 不允许 ({reason})")
        self.history.append({
            "from": self.state.value,
            "to": to.value,
            "reason": reason,
            "detail": detail or {},
        })
        self.state = to
        return to

    def as_dict(self) -> dict:
        return {"orderId": self.order_id, "state": self.state.value, "history": self.history}
