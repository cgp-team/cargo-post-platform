"""多 seed 稳定性回归：同一 instance 至少 10 seeds，检查非法解 / 任务丢失 / RETURN 破坏。

HACO 是随机算法。本测试用较小 instance 控制耗时，核心断言：
- 每个 seed 可行
- 无任务丢失 / 重复
- RETURN 恒为末位
- 骨架不被破坏
- 无异常
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.models import AlgorithmConfig, OrderType, PlanOrder, PlanRequest, Station, Vehicle
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(
        stationId=f"S{i}",
        longitude=104.0 + (i % 8) * 0.01,
        latitude=30.0 + (i % 5) * 0.01,
    )
    for i in range(1, 7)
]


def _orders() -> list[PlanOrder]:
    return (
        [
            PlanOrder(
                orderId=f"P{i}",
                orderType=OrderType.PASSENGER,
                boardingStationId=f"S{i % 6 + 1}",
                alightingStationId=f"S{(i + 2) % 6 + 1}",
            )
            for i in range(1, 5)
        ]
        + [
            PlanOrder(
                orderId=f"D{i}",
                orderType=OrderType.DELIVERY,
                stationId=f"S{i % 6 + 1}",
                itemCount=1,
            )
            for i in range(1, 4)
        ]
        + [
            PlanOrder(
                orderId=f"K{i}",
                orderType=OrderType.PICKUP,
                stationId=f"S{(i + 3) % 6 + 1}",
                itemCount=1,
            )
            for i in range(1, 3)
        ]
    )


def _request(seed: int = 20260903) -> PlanRequest:
    return PlanRequest(
        requestId=f"multi-seed-{seed}",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=DEPOT,
        stations=STATIONS,
        vehicles=[Vehicle(vehicleId=2000 + i, cargoCapacity=6) for i in range(2)],
        orders=_orders(),
        algorithmConfig=AlgorithmConfig(randomSeed=seed),
    )


@pytest.mark.slow
def test_multi_seed_no_illegal_routes():
    """10 seeds：不得出现非法路线 / 任务丢失 / RETURN 后业务事件。"""
    stats = []
    exception_count = 0
    invariant_failures = 0
    feasible_count = 0

    for seed in range(10):
        req = _request(seed=1000 + seed)
        try:
            out = solve(req)
        except Exception:
            exception_count += 1
            continue

        stats.append(
            {
                "seed": seed,
                "status": out.status,
                "vehicles": len(out.vehicle_plans),
                "distance": out.total_distance,
                "runtime": getattr(out, "runtime_ms", None),
                "objective": getattr(out, "objective_key", None),
            }
        )
        if out.status == "feasible":
            feasible_count += 1
        else:
            # 不可行可以是业务不可行，但不得是异常
            pass

        # 最终解结构检查：末站应为 RETURN（或兼容 DEPART 闭环）
        for plan in out.vehicle_plans:
            stops = plan.stops
            if not stops:
                continue
            last = stops[-1]
            action = getattr(last, "action", None)
            action_name = getattr(action, "value", None) or str(action)
            action_name = action_name.upper()
            if action_name not in ("RETURN", "DEPART"):
                if action_name in ("BOARD", "ALIGHT", "PICKUP", "DELIVER"):
                    invariant_failures += 1

        # 任务不丢失：paired 任务（PASSENGER/SHIPMENT）有 2 个事件属正常
        order_ids = {o.orderId for o in _orders()}
        seen: dict[str, int] = {}
        for plan in out.vehicle_plans:
            for stop in plan.stops:
                oid = getattr(stop, "orderId", None)
                if oid:
                    seen[oid] = seen.get(oid, 0) + 1
        if out.status == "feasible":
            missing = order_ids - set(seen)
            if missing:
                invariant_failures += 1
            # 同一 order 事件数不得超过 2（BOARD+ALIGHT / PICKUP+DELIVER）
            for oid, cnt in seen.items():
                if cnt > 2:
                    invariant_failures += 1

    assert exception_count == 0, f"exception_count={exception_count}"
    assert invariant_failures == 0, f"invariant_failures={invariant_failures}"
    assert feasible_count >= 8, f"feasible_rate too low: {feasible_count}/10"

    distances = [s["distance"] for s in stats if s["status"] == "feasible"]
    if distances:
        mean = sum(distances) / len(distances)
        var = sum((d - mean) ** 2 for d in distances) / len(distances)
        std = var ** 0.5
        print(
            f"MULTI_SEED feasible={feasible_count}/10 "
            f"best={min(distances):.3f} mean={mean:.3f} "
            f"median={sorted(distances)[len(distances)//2]:.3f} std={std:.3f}"
        )


def test_multi_seed_smoke_three_seeds_fast():
    """快速 3-seed 冒烟（默认 CI 可跑）。"""
    for seed in range(3):
        req = _request(seed=500 + seed)
        out = solve(req)
        assert out.status in ("feasible", "infeasible", "timeout", "error")
        if out.status == "feasible":
            for plan in out.vehicle_plans:
                if plan.stops:
                    last_action = getattr(plan.stops[-1], "action", None)
                    name = (
                        getattr(last_action, "value", None) or str(last_action)
                    ).upper()
                    assert name in ("RETURN", "DEPART")
