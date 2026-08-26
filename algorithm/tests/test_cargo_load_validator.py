"""Phase 4：真实 Cargo Load 验证器实验。

目标：验证 currentCargoLoad = initialCargo + PICKUP - DELIVERY 的正确行为。

CargoOut/CargoIn 继续保留（出程派送/返程揽收独立累计）。
currentCargoLoad 代表车上真实货物量，先作为 post-solve validator。

规则：
  PICKUP: +quantity
  DELIVER: -quantity
  currentCargo 必须在 [0, cargoCapacity] 内
  currentCargo 不能为负（DELIVERY before PICKUP 且无初始货物时）
"""

from __future__ import annotations

from enum import Enum

import pytest


class StopAction(str, Enum):
    PICKUP = "PICKUP"
    DELIVER = "DELIVER"


class Stop:
    def __init__(self, action: StopAction, quantity: int, order_id: str = ""):
        self.action = action
        self.quantity = quantity
        self.order_id = order_id


def validate_cargo_load(
    stops: list[Stop],
    initial_load: int,
    capacity: int,
) -> tuple[bool, str | None]:
    """验证 cargo load 约束。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    current = initial_load

    for stop in stops:
        if stop.action == StopAction.PICKUP:
            current += stop.quantity
        elif stop.action == StopAction.DELIVER:
            current -= stop.quantity

        if current < 0:
            return False, "CARGO_LOAD_NEGATIVE"

        if current > capacity:
            return False, "CARGO_CAPACITY_EXCEEDED"

    return True, None


# ── Case 1: 正常流程 ──────────────────────────────────────────

def test_case1_normal_flow():
    """initial=0, PICKUP 20, DELIVER 5, PICKUP 10, DELIVER 15
    current: 0 → 20 → 15 → 25 → 10
    capacity=30 → VALID
    """
    stops = [
        Stop(StopAction.PICKUP, 20, "O1"),
        Stop(StopAction.DELIVER, 5, "O2"),
        Stop(StopAction.PICKUP, 10, "O3"),
        Stop(StopAction.DELIVER, 15, "O1"),
    ]
    valid, reason = validate_cargo_load(stops, 0, 30)
    assert valid, f"应 VALID，实际 {reason}"


# ── Case 2: 超容量 ────────────────────────────────────────────

def test_case2_capacity_exceeded():
    """initial=0, PICKUP 20, PICKUP 15
    current: 0 → 20 → 35 > 30 → EXCEEDED
    """
    stops = [
        Stop(StopAction.PICKUP, 20, "O1"),
        Stop(StopAction.PICKUP, 15, "O2"),
    ]
    valid, reason = validate_cargo_load(stops, 0, 30)
    assert not valid
    assert reason == "CARGO_CAPACITY_EXCEEDED"


# ── Case 3: 负载荷（DELIVERY before PICKUP） ──────────────────

def test_case3_negative_load():
    """initial=0, DELIVER 10 → current=-10 < 0 → NEGATIVE"""
    stops = [Stop(StopAction.DELIVER, 10, "O1")]
    valid, reason = validate_cargo_load(stops, 0, 30)
    assert not valid
    assert reason == "CARGO_LOAD_NEGATIVE"


# ── Case 4: 有初始货物 ────────────────────────────────────────

def test_case4_with_initial_cargo():
    """initial=100, DELIVER 50, PICKUP 30, DELIVER 60
    current: 100 → 50 → 80 → 20
    capacity=150 → VALID
    """
    stops = [
        Stop(StopAction.DELIVER, 50, "O1"),
        Stop(StopAction.PICKUP, 30, "O2"),
        Stop(StopAction.DELIVER, 60, "O3"),
    ]
    valid, reason = validate_cargo_load(stops, 100, 150)
    assert valid, f"应 VALID，实际 {reason}"


# ── Case 5: 初始货物 + DELIVERY 超初始 ────────────────────────

def test_case5_initial_cargo_negative():
    """initial=10, DELIVER 20 → current=-10 < 0 → NEGATIVE"""
    stops = [Stop(StopAction.DELIVER, 20, "O1")]
    valid, reason = validate_cargo_load(stops, 10, 30)
    assert not valid
    assert reason == "CARGO_LOAD_NEGATIVE"


# ── Case 6: 正好等于容量 ──────────────────────────────────────

def test_case6_exactly_at_capacity():
    """initial=0, PICKUP 30 → current=30 = capacity → VALID"""
    stops = [Stop(StopAction.PICKUP, 30, "O1")]
    valid, reason = validate_cargo_load(stops, 0, 30)
    assert valid


# ── Case 7: 刚超容量 ──────────────────────────────────────────

def test_case7_just_over_capacity():
    """initial=0, PICKUP 31 → current=31 > 30 → EXCEEDED"""
    stops = [Stop(StopAction.PICKUP, 31, "O1")]
    valid, reason = validate_cargo_load(stops, 0, 30)
    assert not valid
    assert reason == "CARGO_CAPACITY_EXCEEDED"


# ── Case 8: 空 stops ──────────────────────────────────────────

def test_case8_empty_stops():
    """无 stop，initial=10 → VALID"""
    valid, reason = validate_cargo_load([], 10, 30)
    assert valid


# ── Case 9: 初始载荷超容量 ────────────────────────────────────

def test_case9_initial_over_capacity():
    """initial=40 > capacity=30 → EXCEEDED（第一条 stop 之前就超了）"""
    stops = [Stop(StopAction.PICKUP, 1, "O1")]
    valid, reason = validate_cargo_load(stops, 40, 30)
    assert not valid
    assert reason == "CARGO_CAPACITY_EXCEEDED"


# ── Case 10: 初始载荷等于容量 + PICKUP ────────────────────────

def test_case10_initial_at_capacity_plus_pickup():
    """initial=30, PICKUP 1 → 31 > 30 → EXCEEDED"""
    stops = [Stop(StopAction.PICKUP, 1, "O1")]
    valid, reason = validate_cargo_load(stops, 30, 30)
    assert not valid
    assert reason == "CARGO_CAPACITY_EXCEEDED"


# ── Case 11: 典型公交客货邮场景 ────────────────────────────────

def test_case11_typical_bus_scenario():
    """出程派送：车上 100kg 派送件（初始）
    A DELIVER 30 → 70
    B DELIVER 20 → 50
    C PICKUP 40 → 90（返程揽收）
    D DELIVER 10 → 80
    capacity=100 → VALID
    """
    stops = [
        Stop(StopAction.DELIVER, 30, "D1"),
        Stop(StopAction.DELIVER, 20, "D2"),
        Stop(StopAction.PICKUP, 40, "K1"),
        Stop(StopAction.DELIVER, 10, "D3"),
    ]
    valid, reason = validate_cargo_load(stops, 100, 100)
    assert valid, f"典型场景应 VALID，实际 {reason}"


# ── Case 12: CargoOut/CargoIn 与 currentCargoLoad 的区别 ──────

def test_case12_cargo_dimensions_distinct():
    """验证 CargoOut/CargoIn 和 currentCargoLoad 是不同概念。

    CargoOut（出程派送）：DELIVER +itemCount，累计 ≤ capacity
    CargoIn（返程揽收）：PICKUP +itemCount，累计 ≤ capacity
    currentCargoLoad：真实车上货物 = initial + PICKUP - DELIVERY

    场景：initial=50, DELIVER 30, PICKUP 40, DELIVER 20
    CargoOut: 30+20=50 ≤ 50 ✓
    CargoIn: 40 ≤ 50 ✓
    currentCargo: 50→20→60→40, 峰值 60 ≤ 100 ✓

    但如果 capacity=50：
    currentCargo: 50→20→60 > 50 → EXCEEDED
    """
    stops = [
        Stop(StopAction.DELIVER, 30, "D1"),
        Stop(StopAction.PICKUP, 40, "K1"),
        Stop(StopAction.DELIVER, 20, "D2"),
    ]
    # capacity=100: currentCargo peak=60 ≤ 100 → VALID
    valid, _ = validate_cargo_load(stops, 50, 100)
    assert valid

    # capacity=50: currentCargo peak=60 > 50 → EXCEEDED
    valid, reason = validate_cargo_load(stops, 50, 50)
    assert not valid
    assert reason == "CARGO_CAPACITY_EXCEEDED"


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
