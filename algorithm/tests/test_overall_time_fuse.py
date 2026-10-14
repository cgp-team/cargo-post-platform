"""请求级墙钟保险丝（`algorithmConfig.overall_time_limit`）的契约用例。

为什么这几条必须存在（全是实测，不是推演）：
- 改之前 `app/solver.py` 里**没有任何时间管线**，`overall_time_limit=5.0` 这句声明
  只被 HACO 臂内部的 `min(haco, overall)` 间接兑现一次；
- BASELINE 臂一批里会调 `_build_and_solve` **两次**（带时间窗一次 + 「去时间约束」归因一次），
  每次都吃满自己的 `SOLVER_TIME_LIMIT_SECONDS=5` ⇒ 同一批实测 **10.00s**，
  而请求声明的总时限是 5.0s（`limit=1 → wall=2.01`、`limit=5 → wall=10.00`，倍数关系实测可复现）；
- HYBRID 并行跑两臂，改前各拿各的预算 ⇒ 总时限同样不成立。

现在只有一套说法：**终止的正常依据是评估计数；墙钟是保险丝，值由调用方给，
每次可能长时间占用的工作（OR-Tools 搜索 / HACO 主链）都从同一只表领剩余预算；
真的越界时在 warnings 里如实报 `OVERALL_FUSE_TRIPPED`。**
"""

from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.models import AlgorithmMode  # noqa: E402
from app.solver import (  # noqa: E402
    DEFAULT_OVERALL_TIME_LIMIT_SECONDS,
    _overall_limit_seconds,
    solve,
)
from test_haco_141_benchmark import (  # noqa: E402
    _make_request,
    _s1_orders,
    _s1_vehicles,
)

# 保险丝约束的是「可中断的搜索」；预检/建模/解提取是不可中断的固定开销，
# 所以这里不写「wall 必须 < limit」那种一换机器就抖的断言 —— 越界由遥测报出，
# 由上一条机制闸保证「不会翻倍」。口径全文见 docs/algorithm-integration.md「时限与终止口径」。


def _warnings_starting_with(outcome, prefix):
    return [w for w in (outcome.warnings or []) if w.startswith(prefix)]


class _FixedDeadline:
    """鸭子类型的保险丝：本模块只用到 .remaining()。"""

    def __init__(self, remaining: float):
        self._remaining = remaining

    def remaining(self) -> float:
        return self._remaining


def test_each_ortools_search_draws_from_one_shared_fuse():
    """机制闸（不靠墙钟读数，与机器无关）：同一批里第二次搜索只能领剩余量。

    改前的形状：每次调用都写死 `SOLVER_TIME_LIMIT_SECONDS=5`，而一批里最多调两次
    ⇒ 整批 10.00s 对声明 5.0s（实测 limit=1→wall=2.01、limit=5→wall=10.00）。
    现在预算从保险丝领，所以「翻倍」在结构上不可发生 —— 这条断言钉的是结构，
    不是某台机器上跑出来的毫秒数。
    """
    from app.baseline.ortools_solver import SOLVER_TIME_LIMIT_SECONDS, _search_budget_ms

    assert _search_budget_ms(None, SOLVER_TIME_LIMIT_SECONDS) == 5000, "没有保险丝时退回自己的常量"
    assert _search_budget_ms(_FixedDeadline(1.234), SOLVER_TIME_LIMIT_SECONDS) == 1234, "剩余不足就领剩余"
    assert _search_budget_ms(_FixedDeadline(99.0), SOLVER_TIME_LIMIT_SECONDS) == 5000, "不得超过自身常量"
    # 归因那次调用只剩一点点：也不能拿到 0 ⇒ 0 会让 OR-Tools 一次都不迭代，
    # 把「预算用尽」伪装成「无解」，reason_code 就跟着说谎
    assert _search_budget_ms(_FixedDeadline(0.0), SOLVER_TIME_LIMIT_SECONDS) == 50
    first = _search_budget_ms(_FixedDeadline(2.0), SOLVER_TIME_LIMIT_SECONDS)
    second = _search_budget_ms(_FixedDeadline(0.4), SOLVER_TIME_LIMIT_SECONDS)
    assert first + second < SOLVER_TIME_LIMIT_SECONDS * 1000 * 2, (
        "两次调用之和又回到『各吃满一次常量』⇒ 共享保险丝被绕过"
    )


def test_hybrid_shares_one_fuse_and_reports_any_overshoot():
    """两臂共享同一只保险丝；越界必须被如实报出来，而且报的数要和自己对得上。

    口径（与 docs/algorithm-integration.md「时限与终止口径」同一条，不许两处各说一套）：
    `overall_time_limit` 约束的是**可中断的搜索**；预检、建模与解提取是不可中断的固定开销，
    不计入该上限，但也不能被藏起来 ⇒ 一旦整次调用超出上限，必须出现 `OVERALL_FUSE_TRIPPED`，
    且它报的超出毫秒数要等于 `ELAPSED_MS − 上限`（遥测自己撒谎就在这条红）。
    """
    limit = 1.0
    req = _make_request(
        _s1_orders(), vehicles=_s1_vehicles(),
        mode=AlgorithmMode.HYBRID, overall_time_limit=limit, haco_time_limit=limit,
    )
    t0 = time.perf_counter()
    outcome = solve(req)
    wall = time.perf_counter() - t0

    # 唯一真正的硬要求是「会终止」：6× 与基准套件防不终止那一格同因子
    assert wall < limit * 6.0, f"HYBRID 声明 {limit}s 实测 {wall:.2f}s ⇒ 这是不终止，不是慢"

    elapsed_ms = float(next(
        w.split("=", 1)[1] for w in (outcome.warnings or []) if w.startswith("ELAPSED_MS=")
    ))
    trips = [w for w in (outcome.warnings or []) if w.startswith("OVERALL_FUSE_TRIPPED=")]
    if elapsed_ms > limit * 1000:
        assert trips, (
            f"整次调用 {elapsed_ms:.0f}ms 已超上限 {limit * 1000:.0f}ms 却没报越界 ⇒ 固定开销被藏起来了"
        )
        reported = float(trips[0].split("=", 1)[1].rstrip("ms"))
        assert abs(reported - (elapsed_ms - limit * 1000)) < 1.0, (
            f"越界量报 {reported}ms，而 ELAPSED_MS 算出来是 {elapsed_ms - limit * 1000:.1f}ms ⇒ 遥测自相矛盾"
        )
    else:
        assert not trips, "没越界却报了 OVERALL_FUSE_TRIPPED"


def test_non_positive_or_invalid_limit_falls_back_to_default_not_zero():
    """0 不是「立刻超时」的合法表达：它会把每个请求都判成无解，读起来像算法坏了。"""
    for bad in (0, -3.0, "fast", None):
        req = _make_request(_s1_orders(), vehicles=_s1_vehicles(),
                            mode=AlgorithmMode.BASELINE)
        if bad is not None:
            req.algorithmConfig.overall_time_limit = bad
        else:
            req.algorithmConfig = req.algorithmConfig.model_copy(update={"overall_time_limit": None})
        assert _overall_limit_seconds(req) == DEFAULT_OVERALL_TIME_LIMIT_SECONDS, (
            f"overall_time_limit={bad!r} 没有退回默认档 ⇒ 非法值被当成预算用"
        )
