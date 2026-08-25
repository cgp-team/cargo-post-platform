"""算法稳定性测试（Phase 13）：随机打乱订单输入顺序 60 次，
比较 可行性率 / 车辆数 / 总里程，验证结果不因订单输入顺序轻微变化而严重不稳定。

指标口径（对应任务书 四十三/四十四）：
- 可行性率：打乱后应全部与基线一致（不出现"个别顺序下无解"的抖动）；
- 车辆数：求解器确定性 + 固定成本优先单车，打乱不应改变用车数；
- 总里程：同批订单不同访问顺序下里程允许小幅波动（<30%），但不应剧烈变化。
"""

from __future__ import annotations

import random

from app.models import OrderType, PlanOrder, PlanRequest, Station, Vehicle
from app.solver import solve

DEPOT = Station(stationId="S0", longitude=104.000, latitude=30.000)
STATIONS = [
    Station(stationId=f"S{i}", longitude=104.0 + (i % 10) * 0.01, latitude=30.0 + (i % 7) * 0.01)
    for i in range(1, 11)
]


def make_orders() -> list[PlanOrder]:
    """13 客 + 6 派 + 6 揽（贴近满规模 25 单），覆盖客运/货运/揽收三类。"""
    return (
        [PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                   boardingStationId=f"S{i % 10 + 1}", alightingStationId=f"S{(i + 4) % 10 + 1}")
         for i in range(1, 14)]
        + [PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                     stationId=f"S{i % 10 + 1}", itemCount=1) for i in range(1, 7)]
        + [PlanOrder(orderId=f"K{i}", orderType=OrderType.PICKUP,
                     stationId=f"S{(i + 6) % 10 + 1}", itemCount=1) for i in range(7, 13)]
    )


def solve_with(orders: list[PlanOrder]):
    req = PlanRequest(
        requestId="r", batchStart="2026-08-23T08:00:00+08:00", batchEnd="2026-08-23T08:30:00+08:00",
        depot=DEPOT, stations=STATIONS,
        vehicles=[Vehicle(vehicleId=1000 + i, cargoCapacity=4) for i in range(3)],
        orders=orders,
    )
    return solve(req)


def test_stability_across_shuffled_input_orders() -> None:
    orders = make_orders()
    random.seed(42)
    base = solve_with(orders)
    assert base.status == "feasible", f"基线应可行: {base.reason_code}"

    metrics: list[dict] = []
    for _ in range(60):
        shuffled = orders[:]
        random.shuffle(shuffled)
        out = solve_with(shuffled)
        metrics.append({
            "feasible": out.status == "feasible",
            "vehicles": len(out.vehicle_plans),
            "distance": out.total_distance,
            "reason": out.reason_code,
        })

    # 可行性率：打乱后全部与基线一致（无抖动无解）
    assert all(m["feasible"] for m in metrics), (
        f"打乱输入后出现不可行: {[m['reason'] for m in metrics if not m['feasible']]}"
    )

    # 车辆数：求解确定性 + 优先单车，打乱不应改变用车数
    vehicle_counts = {m["vehicles"] for m in metrics}
    assert len(vehicle_counts) == 1, f"车辆数应稳定，实际 {vehicle_counts}"

    # 总里程：波动控制在均值 30% 内（允许访问顺序带来小幅差异，但不应剧烈变化）
    distances = [m["distance"] for m in metrics]
    avg = sum(distances) / len(distances)
    assert avg > 0
    for d in distances:
        assert abs(d - avg) / avg < 0.30, f"总里程波动过大: {d} vs 均值 {avg:.3f}"


def test_feasibility_rate_consistent_across_orderings() -> None:
    """另一组更大运力的场景：打乱 80 次，可行性率恒为 100%（无输入顺序导致的误判）。"""
    orders = (
        [PlanOrder(orderId=f"P{i}", orderType=OrderType.PASSENGER,
                   boardingStationId=f"S{i % 10 + 1}", alightingStationId=f"S{(i + 3) % 10 + 1}")
         for i in range(1, 11)]
        + [PlanOrder(orderId=f"D{i}", orderType=OrderType.DELIVERY,
                     stationId=f"S{i % 10 + 1}", itemCount=1) for i in range(1, 9)]
    )
    random.seed(7)
    feasible = 0
    total = 80
    for _ in range(total):
        shuffled = orders[:]
        random.shuffle(shuffled)
        out = solve_with(shuffled)
        if out.status == "feasible":
            feasible += 1
    assert feasible == total, f"可行性率应 100%，实际 {feasible}/{total}"
