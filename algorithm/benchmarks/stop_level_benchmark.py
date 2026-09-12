"""Stop-Level Representation 独立 Benchmark。

比较：
A. OR-Tools baseline
B. HACO 2.0 task-level
C. Stop-Level Greedy
D. Stop-Level Passenger-First
E. Stop-Level Interleaved
F. Stop-Level + Local Search
G. Stop-Level + HACO

每个方案运行 20 seeds。
"""

from __future__ import annotations

import json
import random
import statistics
import sys
import time
from math import hypot
from pathlib import Path

# 确保可以导入 app 模块
sys.path.insert(0, str(Path(__file__).parent.parent))

from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    Station,
    Vehicle,
)
from app.solver import solve


def make_benchmark_request(seed: int) -> PlanRequest:
    """构造 8-order benchmark 请求。"""
    depot = Station(stationId="S0", longitude=104.000, latitude=30.000)
    stations = [
        Station(stationId=f"S{i}", longitude=104.0 + i * 0.01, latitude=30.0 + i * 0.01)
        for i in range(1, 6)
    ]

    orders = [
        PlanOrder(
            orderId=f"P{i}",
            orderType=OrderType.PASSENGER,
            boardingStationId=f"S{(i % 5) + 1}",
            alightingStationId=f"S{((i + 2) % 5) + 1}",
        )
        for i in range(5)
    ] + [
        PlanOrder(
            orderId=f"D{i}",
            orderType=OrderType.DELIVERY,
            stationId=f"S{(i % 5) + 1}",
            itemCount=1,
        )
        for i in range(3)
    ]

    return PlanRequest(
        requestId=f"bench-{seed}",
        batchStart="2026-08-23T08:00:00+08:00",
        batchEnd="2026-08-23T18:00:00+08:00",
        depot=depot,
        stations=stations,
        vehicles=[
            Vehicle(vehicleId=i, passengerCapacity=5, cargoCapacity=4)
            for i in range(1, 3)
        ],
        orders=orders,
    )


def get_station_map(request: PlanRequest) -> dict:
    """获取站点映射。"""
    station_map = {s.stationId: s for s in request.stations}
    station_map[request.depot.stationId] = request.depot
    return station_map


def run_baseline(request: PlanRequest) -> dict:
    """运行 OR-Tools baseline。"""
    req = request.model_copy(
        update={
            "algorithmConfig": AlgorithmConfig(algorithmMode=AlgorithmMode.BASELINE)
        }
    )
    start = time.monotonic()
    result = solve(req)
    elapsed = time.monotonic() - start

    return {
        "distance": result.total_distance,
        "vehicles": len(result.vehicle_plans),
        "feasible": result.status == "feasible",
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_station_sequence(result),
    }


def run_haco_task_level(request: PlanRequest, seed: int) -> dict:
    """运行 HACO 2.0 task-level。"""
    req = request.model_copy(
        update={
            "requestId": f"haco-tl-{seed}",
            "algorithmConfig": AlgorithmConfig(
                algorithmMode=AlgorithmMode.HACO,
                randomSeed=seed,
                ant_count=8,
                max_iterations=10,
            ),
        }
    )
    start = time.monotonic()
    result = solve(req)
    elapsed = time.monotonic() - start

    return {
        "distance": result.total_distance,
        "vehicles": len(result.vehicle_plans),
        "feasible": result.status == "feasible",
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_station_sequence(result),
    }


def run_stop_level_greedy(request: PlanRequest, seed: int) -> dict:
    """运行 Stop-Level Greedy。"""
    from app.haco.stop_level.constructor import construct_greedy_stop_level
    from app.haco.stop_level.evaluator import evaluate_solution
    from app.haco.stop_level.models import Request, StopLevelRoute
    import app.haco.stop_level.evaluator as eval_module

    station_map = get_station_map(request)
    requests = _encode_stop_requests(request)
    templates = _build_stop_templates(request)

    eval_module.solution_requests = {r.request_id: r for r in requests}

    rng = random.Random(seed)
    start = time.monotonic()
    solution = construct_greedy_stop_level(requests, templates, station_map, None, rng)
    obj = evaluate_solution(solution, station_map)
    elapsed = time.monotonic() - start

    return {
        "distance": obj.total_distance,
        "vehicles": obj.vehicle_count,
        "feasible": obj.feasible,
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_stop_station_sequence(solution),
        "activity_sequence": _extract_activity_sequence(solution),
    }


def run_stop_level_passenger_first(request: PlanRequest, seed: int) -> dict:
    """运行 Stop-Level Passenger-First。"""
    from app.haco.stop_level.constructor import construct_passenger_first_stop_level
    from app.haco.stop_level.evaluator import evaluate_solution
    from app.haco.stop_level.models import Request, StopLevelRoute
    import app.haco.stop_level.evaluator as eval_module

    station_map = get_station_map(request)
    requests = _encode_stop_requests(request)
    templates = _build_stop_templates(request)

    eval_module.solution_requests = {r.request_id: r for r in requests}

    rng = random.Random(seed)
    start = time.monotonic()
    solution = construct_passenger_first_stop_level(requests, templates, station_map, None, rng)
    obj = evaluate_solution(solution, station_map)
    elapsed = time.monotonic() - start

    return {
        "distance": obj.total_distance,
        "vehicles": obj.vehicle_count,
        "feasible": obj.feasible,
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_stop_station_sequence(solution),
        "activity_sequence": _extract_activity_sequence(solution),
    }


def run_stop_level_interleaved(request: PlanRequest, seed: int) -> dict:
    """运行 Stop-Level Interleaved。"""
    from app.haco.stop_level.constructor import construct_interleaved_stop_level
    from app.haco.stop_level.evaluator import evaluate_solution
    from app.haco.stop_level.models import Request, StopLevelRoute
    import app.haco.stop_level.evaluator as eval_module

    station_map = get_station_map(request)
    requests = _encode_stop_requests(request)
    templates = _build_stop_templates(request)

    eval_module.solution_requests = {r.request_id: r for r in requests}

    rng = random.Random(seed)
    start = time.monotonic()
    solution = construct_interleaved_stop_level(requests, templates, station_map, None, rng)
    obj = evaluate_solution(solution, station_map)
    elapsed = time.monotonic() - start

    return {
        "distance": obj.total_distance,
        "vehicles": obj.vehicle_count,
        "feasible": obj.feasible,
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_stop_station_sequence(solution),
        "activity_sequence": _extract_activity_sequence(solution),
    }


def run_stop_level_local_search(request: PlanRequest, seed: int) -> dict:
    """运行 Stop-Level + Local Search。"""
    from app.haco.stop_level.constructor import construct_interleaved_stop_level
    from app.haco.stop_level.evaluator import evaluate_solution
    from app.haco.stop_level.local_search import stop_level_local_search
    from app.haco.stop_level.models import Request, StopLevelRoute
    from app.haco.config import HacoConfig
    import app.haco.stop_level.evaluator as eval_module

    station_map = get_station_map(request)
    requests = _encode_stop_requests(request)
    templates = _build_stop_templates(request)

    eval_module.solution_requests = {r.request_id: r for r in requests}

    rng = random.Random(seed)
    config = HacoConfig(ant_count=4, max_iterations=5, local_search_rounds=3, random_seed=seed)

    start = time.monotonic()
    solution = construct_interleaved_stop_level(requests, templates, station_map, None, rng)
    solution = stop_level_local_search(solution, station_map, None, config, rng)
    obj = evaluate_solution(solution, station_map)
    elapsed = time.monotonic() - start

    return {
        "distance": obj.total_distance,
        "vehicles": obj.vehicle_count,
        "feasible": obj.feasible,
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_stop_station_sequence(solution),
        "activity_sequence": _extract_activity_sequence(solution),
    }


def run_stop_level_haco(request: PlanRequest, seed: int) -> dict:
    """运行 Stop-Level + HACO。"""
    from app.haco.stop_level.solver import solve_stop_level
    from app.haco.config import HacoConfig

    station_map = get_station_map(request)
    config = HacoConfig(ant_count=8, max_iterations=10, random_seed=seed, haco_time_limit=5.0)

    start = time.monotonic()
    solution, obj = solve_stop_level(request, station_map, None, config)
    elapsed = time.monotonic() - start

    return {
        "distance": obj.total_distance,
        "vehicles": obj.vehicle_count,
        "feasible": obj.feasible,
        "runtime_ms": round(elapsed * 1000, 1),
        "station_sequence": _extract_stop_station_sequence(solution),
        "activity_sequence": _extract_activity_sequence(solution),
    }


# ── 辅助函数 ──────────────────────────────────────────────


def _extract_station_sequence(result) -> list[str]:
    """从 PlanResult 提取站点序列。"""
    seq = []
    for plan in result.vehicle_plans:
        for stop in plan.stops:
            if stop.stationId not in seq:
                seq.append(stop.stationId)
    return seq


def _extract_stop_station_sequence(solution) -> list[str]:
    """从 StopLevelSolution 提取站点序列。"""
    seq = []
    for route in solution.routes.values():
        for activity in route.activities:
            if activity.station_id not in seq:
                seq.append(activity.station_id)
    return seq


def _extract_activity_sequence(solution) -> list[str]:
    """从 StopLevelSolution 提取活动序列。"""
    seq = []
    for route in solution.routes.values():
        for activity in route.activities:
            seq.append(f"{activity.action.value}@{activity.station_id}({activity.request_id or '-'})")
    return seq


def _encode_stop_requests(request: PlanRequest):
    """编码请求为 Stop-Level Request。"""
    from app.haco.stop_level.models import Request

    requests = []
    for order in request.orders:
        if order.orderType == OrderType.PASSENGER:
            requests.append(Request(
                request_id=f"P:{order.orderId}",
                request_type="PASSENGER",
                pickup_station=order.boardingStationId,
                delivery_station=order.alightingStationId,
                size=1,
            ))
        elif order.orderType == OrderType.DELIVERY:
            requests.append(Request(
                request_id=f"D:{order.orderId}",
                request_type="DELIVERY",
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
            ))
        elif order.orderType == OrderType.PICKUP:
            requests.append(Request(
                request_id=f"K:{order.orderId}",
                request_type="PICKUP",
                pickup_station=order.stationId,
                delivery_station=order.stationId,
                size=order.itemCount,
            ))
    return requests


def _build_stop_templates(request: PlanRequest):
    """构建 Stop-Level 路线模板。"""
    from app.haco.stop_level.models import StopLevelRoute

    templates = []
    for i, vehicle in enumerate(request.vehicles):
        templates.append(StopLevelRoute(
            vehicle_index=i,
            vehicle_id=vehicle.vehicleId,
            passenger_capacity=vehicle.passengerCapacity,
            cargo_capacity=vehicle.cargoCapacity,
            initial_passenger_load=vehicle.initialPassengerLoad,
            initial_cargo_load=vehicle.initialCargoLoad,
            skeleton=vehicle.skeleton or [],
            depot_station=request.depot.stationId,
        ))
    return templates


def compute_stats(values: list[float]) -> dict:
    """计算统计指标。"""
    if not values:
        return {"mean": 0, "median": 0, "best": 0, "worst": 0, "std": 0, "p50": 0, "p90": 0}
    sorted_vals = sorted(values)
    n = len(sorted_vals)
    return {
        "mean": round(statistics.mean(values), 4),
        "median": round(statistics.median(values), 4),
        "best": round(min(values), 4),
        "worst": round(max(values), 4),
        "std": round(statistics.stdev(values), 4) if n > 1 else 0,
        "p50": round(sorted_vals[n // 2], 4),
        "p90": round(sorted_vals[int(n * 0.9)], 4) if n > 1 else sorted_vals[0],
    }


# ── 主函数 ──────────────────────────────────────────────


def main():
    """运行 benchmark。"""
    SEEDS = 20
    seeds = list(range(1, SEEDS + 1))

    methods = [
        ("A. OR-Tools Baseline", run_baseline),
        ("B. HACO 2.0 Task-Level", run_haco_task_level),
        ("C. Stop-Level Greedy", run_stop_level_greedy),
        ("D. Stop-Level Passenger-First", run_stop_level_passenger_first),
        ("E. Stop-Level Interleaved", run_stop_level_interleaved),
        ("F. Stop-Level + Local Search", run_stop_level_local_search),
        ("G. Stop-Level + HACO", run_stop_level_haco),
    ]

    results = {}

    for method_name, method_fn in methods:
        print(f"\n{'='*60}")
        print(f"Running: {method_name}")
        print(f"{'='*60}")

        distances = []
        runtimes = []
        feasible_count = 0
        best_result = None
        best_distance = float("inf")

        for seed in seeds:
            request = make_benchmark_request(seed)

            try:
                if method_name == "A. OR-Tools Baseline":
                    result = method_fn(request)
                else:
                    result = method_fn(request, seed)

                if result["feasible"]:
                    feasible_count += 1
                    distances.append(result["distance"])
                    runtimes.append(result["runtime_ms"])

                    if result["distance"] < best_distance:
                        best_distance = result["distance"]
                        best_result = result

                status = "OK" if result["feasible"] else "FAIL"
                print(f"  seed {seed:2d}: {status} dist={result['distance']:.4f} time={result['runtime_ms']:.0f}ms")

            except Exception as e:
                print(f"  seed {seed:2d}: ERROR {e}")

        # 统计
        stats = compute_stats(distances) if distances else {"mean": 0, "median": 0, "best": 0, "worst": 0, "std": 0, "p50": 0, "p90": 0}

        results[method_name] = {
            "feasible_rate": f"{feasible_count}/{SEEDS}",
            "stats": stats,
            "avg_runtime_ms": round(statistics.mean(runtimes), 1) if runtimes else 0,
            "best_station_sequence": best_result.get("station_sequence", []) if best_result else [],
            "best_activity_sequence": best_result.get("activity_sequence", []) if best_result else [],
        }

        print(f"\n  Summary:")
        print(f"    Feasible: {feasible_count}/{SEEDS}")
        print(f"    Best:     {stats['best']:.4f}")
        print(f"    Mean:     {stats['mean']:.4f}")
        print(f"    Median:   {stats['median']:.4f}")
        print(f"    Std:      {stats['std']:.4f}")
        print(f"    P90:      {stats['p90']:.4f}")
        print(f"    Runtime:  {results[method_name]['avg_runtime_ms']:.0f}ms")
        if best_result:
            print(f"    Best seq: {' → '.join(best_result.get('station_sequence', []))}")

    # ── 对比表 ──────────────────────────────────────────────

    print(f"\n{'='*80}")
    print("COMPARISON TABLE")
    print(f"{'='*80}")
    print(f"{'Method':<35} {'Best':>8} {'Mean':>8} {'Median':>8} {'Std':>8} {'P90':>8} {'Feasible':>10} {'Runtime':>10}")
    print("-" * 80)

    baseline_best = None
    for method_name, data in results.items():
        stats = data["stats"]
        if "Baseline" in method_name:
            baseline_best = stats["best"]
        print(f"{method_name:<35} {stats['best']:>8.4f} {stats['mean']:>8.4f} {stats['median']:>8.4f} {stats['std']:>8.4f} {stats['p90']:>8.4f} {data['feasible_rate']:>10} {data['avg_runtime_ms']:>8.0f}ms")

    # Gap to baseline
    if baseline_best and baseline_best > 0:
        print(f"\n{'='*80}")
        print("GAP TO BASELINE")
        print(f"{'='*80}")
        for method_name, data in results.items():
            stats = data["stats"]
            if stats["best"] > 0:
                gap = (stats["best"] - baseline_best) / baseline_best * 100
                print(f"{method_name:<35} gap = {gap:>+.1f}%")

    # 保存结果
    output_file = Path(__file__).parent.parent / "artifacts" / "stop-level-benchmark.json"
    output_file.parent.mkdir(exist_ok=True)
    with open(output_file, "w") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"\nResults saved to: {output_file}")


if __name__ == "__main__":
    main()
