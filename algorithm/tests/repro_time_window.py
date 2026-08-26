"""Phase 6：任务时间段复现实验。

目标：验证 batchStart/batchEnd 时间约束。

第一阶段：post-solve 时间验证（不修改 Solver）。
第二阶段：Time Dimension 放入 RoutingModel。

OR-Tools Time Dimension：
  - 每段弧有 duration（行驶时间）
  - 每个节点有 service time（服务时间）
  - CumulVar(node) = 到达该节点时的累计时间
  - 车辆出发时间可以固定（batchStart）
  - 车辆返回时间必须 ≤ batchEnd

默认服务时间常量：
  DEFAULT_BOARD_SECONDS = 30
  DEFAULT_ALIGHT_SECONDS = 20
  DEFAULT_PICKUP_SECONDS = 60
  DEFAULT_DELIVERY_SECONDS = 60
"""

from __future__ import annotations

from enum import Enum

import pytest


class StopAction(str, Enum):
    DEPART = "DEPART"
    BOARD = "BOARD"
    ALIGHT = "ALIGHT"
    DELIVER = "DELIVER"
    PICKUP = "PICKUP"
    PASS = "PASS"
    RETURN = "RETURN"


# 默认服务时间（秒）
DEFAULT_BOARD_SECONDS = 30
DEFAULT_ALIGHT_SECONDS = 20
DEFAULT_PICKUP_SECONDS = 60
DEFAULT_DELIVERY_SECONDS = 60
DEFAULT_PASS_SECONDS = 0


def service_duration(action: StopAction) -> int:
    """根据 stop action 返回服务时间（秒）。"""
    if action == StopAction.BOARD:
        return DEFAULT_BOARD_SECONDS
    if action == StopAction.ALIGHT:
        return DEFAULT_ALIGHT_SECONDS
    if action == StopAction.PICKUP:
        return DEFAULT_PICKUP_SECONDS
    if action == StopAction.DELIVER:
        return DEFAULT_DELIVERY_SECONDS
    if action == StopAction.PASS:
        return DEFAULT_PASS_SECONDS
    return 0  # DEPART, RETURN


class Stop:
    def __init__(self, action: StopAction, segment_duration: float | None = None):
        self.action = action
        self.segmentDuration = segment_duration  # 行驶秒数


def validate_time_window(
    stops: list[Stop],
    batch_start_seconds: int,
    batch_end_seconds: int,
) -> tuple[bool, str | None]:
    """验证时间窗口约束。

    Returns:
        (True, None) if valid
        (False, reason_code) if invalid
    """
    current_time = batch_start_seconds

    for i, stop in enumerate(stops):
        # 行驶时间（第一段 DEPART 无行驶时间）
        if i > 0 and stop.segmentDuration is not None:
            current_time += int(stop.segmentDuration)

        # 服务时间
        current_time += service_duration(stop.action)

    if current_time > batch_end_seconds:
        return False, "TIME_WINDOW_EXCEEDED"

    return True, None


# ── Case 1: 正常时间窗口 ──────────────────────────────────────

def test_case1_normal_time_window():
    """batchStart=08:00, batchEnd=12:00 (4 hours = 14400s)
    DEPART → BOARD(30s) → 行驶(300s) → ALIGHT(20s) → RETURN
    总时间: 0 + 30 + 300 + 20 + 0 = 350s ≤ 14400s → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, 300),
        Stop(StopAction.ALIGHT, 100),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 0, 14400)
    assert valid, f"应 VALID，实际 {reason}"


# ── Case 2: 超出时间窗口 ──────────────────────────────────────

def test_case2_time_window_exceeded():
    """batchEnd=600s
    DEPART → BOARD(30s) → 行驶(500s) → ALIGHT(20s) → 行驶(200s) → RETURN
    总时间: 30 + 500 + 20 + 200 = 750s > 600s → EXCEEDED
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, 500),
        Stop(StopAction.ALIGHT, 200),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 0, 600)
    assert not valid
    assert reason == "TIME_WINDOW_EXCEEDED"


# ── Case 3: 正好等于时间窗口 ──────────────────────────────────

def test_case3_exactly_at_time_window():
    """batchEnd=450s
    DEPART → 行驶(300s) + BOARD(30s) → 行驶(100s) + ALIGHT(20s) → RETURN(0s)
    总时间: 0 + 300+30 + 100+20 = 450s = 450s → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, 300),
        Stop(StopAction.ALIGHT, 100),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 0, 450)
    assert valid


# ── Case 4: 多站点 + 货运 ─────────────────────────────────────

def test_case4_multiple_stops_with_cargo():
    """batchStart=28800 (08:00), batchEnd=43200 (12:00)
    DEPART
    → BOARD(30s) + 行驶(300s)
    → PICKUP(60s) + 行驶(200s)
    → DELIVER(60s) + 行驶(150s)
    → ALIGHT(20s) + 行驶(100s)
    → RETURN
    总时间: 30+300+60+200+60+150+20+100 = 920s
    28800+920=29720 ≤ 43200 → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, 300),
        Stop(StopAction.PICKUP, 200),
        Stop(StopAction.DELIVER, 150),
        Stop(StopAction.ALIGHT, 100),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 28800, 43200)
    assert valid, f"应 VALID，实际 {reason}"


# ── Case 5: 骨架 PASS 节点 ────────────────────────────────────

def test_case5_skeleton_pass_stops():
    """DEPART → PASS(0s) → PASS(0s) → BOARD(30s) → ALIGHT(20s) → RETURN
    行驶时间: 100+100+200+100 = 500s
    服务时间: 0+0+30+20 = 50s
    总时间: 550s ≤ 1000s → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.PASS, 100),
        Stop(StopAction.PASS, 100),
        Stop(StopAction.BOARD, 200),
        Stop(StopAction.ALIGHT, 100),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 0, 1000)
    assert valid


# ── Case 6: 无行驶时间（欧氏路径） ────────────────────────────

def test_case6_no_segment_duration():
    """segmentDuration=None（欧氏路径）→ 只计算服务时间
    DEPART → BOARD(30s) → ALIGHT(20s) → RETURN
    总时间: 30+20 = 50s ≤ 100s → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, None),
        Stop(StopAction.ALIGHT, None),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 0, 100)
    assert valid


# ── Case 7: 批次开始时间偏移 ──────────────────────────────────

def test_case7_batch_start_offset():
    """batchStart=36000 (10:00), batchEnd=43200 (12:00)
    DEPART → BOARD(30s) → 行驶(3600s) → ALIGHT(20s) → RETURN
    总时间: 30+3600+20 = 3650s
    36000+3650=39650 ≤ 43200 → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, 3600),
        Stop(StopAction.ALIGHT, 100),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 36000, 43200)
    assert valid


# ── Case 8: 超出 1 分钟 ───────────────────────────────────────

def test_case8_just_over_time_window():
    """DEPART → 行驶(300s) + BOARD(30s) → 行驶(100s) + ALIGHT(20s) → RETURN
    总时间: 300+30+100+20 = 450s

    batchEnd=451s → 450 ≤ 451 → VALID
    batchEnd=449s → 450 > 449 → EXCEEDED
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.BOARD, 300),
        Stop(StopAction.ALIGHT, 100),
        Stop(StopAction.RETURN),
    ]
    valid, _ = validate_time_window(stops, 0, 451)
    assert valid

    valid, reason = validate_time_window(stops, 0, 449)
    assert not valid
    assert reason == "TIME_WINDOW_EXCEEDED"


# ── Case 9: 业务场景 —— 公交线路 ──────────────────────────────

def test_case9_bus_route_scenario():
    """09:00-12:00 公交线路
    DEPART → PASS A(0s) → BOARD 5人(150s) → PASS B(0s)
    → ALIGHT 3人(60s) → PICKUP 货(60s) → DELIVER 货(60s)
    → PASS C(0s) → ALIGHT 2人(40s) → RETURN

    行驶: 200+150+100+200+150+100 = 900s
    服务: 0+150+0+60+60+60+0+40+0 = 370s
    总时间: 1270s ≤ 10800s (3h) → VALID
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.PASS, 200),
        Stop(StopAction.BOARD, 150),
        Stop(StopAction.PASS, 100),
        Stop(StopAction.ALIGHT, 200),
        Stop(StopAction.PICKUP, 150),
        Stop(StopAction.DELIVER, 100),
        Stop(StopAction.PASS, 0),
        Stop(StopAction.ALIGHT, 0),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 32400, 43200)  # 09:00-12:00
    assert valid, f"公交线路应 VALID，实际 {reason}"


# ── Case 10: 超时场景 ─────────────────────────────────────────

def test_case10_bus_route_exceeds_time():
    """同 Case 9 但 batchEnd=10:00 (36000s)
    总时间: 1270s
    32400+1270=33670 ≤ 36000 → VALID

    但如果行驶时间更长：
    行驶: 2000+1500+1000+2000+1500+1000 = 9000s
    服务: 370s
    总时间: 9370s
    32400+9370=41770 > 36000 → EXCEEDED
    """
    stops = [
        Stop(StopAction.DEPART),
        Stop(StopAction.PASS, 2000),
        Stop(StopAction.BOARD, 1500),
        Stop(StopAction.PASS, 1000),
        Stop(StopAction.ALIGHT, 2000),
        Stop(StopAction.PICKUP, 1500),
        Stop(StopAction.DELIVER, 1000),
        Stop(StopAction.PASS, 0),
        Stop(StopAction.ALIGHT, 0),
        Stop(StopAction.RETURN),
    ]
    valid, reason = validate_time_window(stops, 32400, 36000)
    assert not valid
    assert reason == "TIME_WINDOW_EXCEEDED"


# ── 主入口 ────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
