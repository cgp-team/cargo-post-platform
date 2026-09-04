"""HACO-CPS 2.0 全局局部搜索：操作 GlobalRouteGenome。

所有操作都是 block-aware：
- Passenger: BOARD+ALIGHT 整体移动
- Shipment: PICKUP+DELIVERY 整体移动
"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

from .global_evaluator import evaluate_genome
from .route_genome import GlobalRouteGenome

if TYPE_CHECKING:
    from .config import HacoConfig
    from ..distance import DistanceMatrix
    from ..models import Station


def global_local_search(
    genome: GlobalRouteGenome,
    station_map: dict,
    matrix: DistanceMatrix | None,
    config: HacoConfig,
    rng: random.Random,
) -> GlobalRouteGenome:
    """对 GlobalRouteGenome 执行多轮局部搜索。"""
    best = genome.copy()
    best_obj = evaluate_genome(best, station_map, matrix)

    for _ in range(config.local_search_rounds):
        improved = False

        # Relocate（使用副本避免修改原始）
        test_genome = best.copy()
        new_genome = _relocate(test_genome, station_map, matrix, config, rng)
        if new_genome and len(new_genome.get_all_tasks()) == len(best.get_all_tasks()):
            new_obj = evaluate_genome(new_genome, station_map, matrix)
            if new_obj < best_obj:
                best = new_genome
                best_obj = new_obj
                improved = True

        # Swap（使用副本）
        test_genome = best.copy()
        new_genome = _swap(test_genome, station_map, matrix, config, rng)
        if new_genome and len(new_genome.get_all_tasks()) == len(best.get_all_tasks()):
            new_obj = evaluate_genome(new_genome, station_map, matrix)
            if new_obj < best_obj:
                best = new_genome
                best_obj = new_obj
                improved = True

        # 2-opt（使用副本）
        test_genome = best.copy()
        new_genome = _two_opt(test_genome, station_map, matrix, config, rng)
        if new_genome and len(new_genome.get_all_tasks()) == len(best.get_all_tasks()):
            new_obj = evaluate_genome(new_genome, station_map, matrix)
            if new_obj < best_obj:
                best = new_genome
                best_obj = new_obj
                improved = True

        if not improved:
            break

    return best


def _relocate(genome, station_map, matrix, config, rng) -> GlobalRouteGenome | None:
    """Relocate：将一个任务从一条路线移到另一条。"""
    all_tasks = genome.get_all_tasks()
    if not all_tasks:
        return None

    task_id = rng.choice(all_tasks)
    result = genome.remove_task(task_id)
    if result is None:
        return None

    src_vi, src_pos = result
    task = genome.task_blocks.get(task_id)
    if not task:
        # 恢复任务
        genome.insert_task(src_vi, src_pos, task_id)
        return None

    # 找最佳新位置
    best_vi = src_vi
    best_pos = src_pos
    best_score = float("inf")

    for vi in genome.vehicle_routes.keys():
        route = genome.get_route(vi)
        for pos in range(len(route) + 1):
            score = _relocation_score(task, vi, pos, genome, station_map, matrix)
            if score < best_score:
                best_score = score
                best_vi = vi
                best_pos = pos

    # 创建新基因组并插入任务
    new_genome = genome.copy()
    new_genome.insert_task(best_vi, best_pos, task_id)

    # 验证任务数量
    if len(new_genome.get_all_tasks()) != len(all_tasks):
        # 恢复原始基因组
        genome.insert_task(src_vi, src_pos, task_id)
        return None

    return new_genome


def _swap(genome, station_map, matrix, config, rng) -> GlobalRouteGenome | None:
    """Swap：交换两个任务的位置。"""
    all_tasks = genome.get_all_tasks()
    if len(all_tasks) < 2:
        return None

    t1_id, t2_id = rng.sample(all_tasks, 2)
    r1 = genome.remove_task(t1_id)
    r2 = genome.remove_task(t2_id)

    if r1 is None or r2 is None:
        # 恢复任务
        if r2 is not None:
            genome.insert_task(r2[0], r2[1], t2_id)
        if r1 is not None:
            genome.insert_task(r1[0], r1[1], t1_id)
        return None

    vi1, pos1 = r1
    vi2, pos2 = r2

    new_genome = genome.copy()
    new_genome.insert_task(vi2, pos2, t1_id)
    new_genome.insert_task(vi1, pos1, t2_id)

    # 验证任务数量
    if len(new_genome.get_all_tasks()) != len(all_tasks):
        # 恢复原始基因组
        genome.insert_task(vi2, pos2, t2_id)
        genome.insert_task(vi1, pos1, t1_id)
        return None

    return new_genome


def _two_opt(genome, station_map, matrix, config, rng) -> GlobalRouteGenome | None:
    """2-opt：在同一车辆路线内反转一段子序列。"""
    active_vis = [vi for vi, route in genome.vehicle_routes.items() if len(route) >= 2]
    if not active_vis:
        return None

    vi = rng.choice(active_vis)
    route = genome.get_route(vi)

    if len(route) < 2:
        return None

    # 随机选择两个位置
    i = rng.randrange(len(route) - 1)
    j = rng.randrange(i + 1, len(route))

    new_genome = genome.copy()
    new_route = list(route)
    new_route[i:j+1] = reversed(new_route[i:j+1])
    new_genome.set_route(vi, new_route)
    return new_genome


def _relocation_score(task, vi, pos, genome, station_map, matrix) -> float:
    """计算重新定位的得分。"""
    from math import hypot

    route = genome.get_route(vi)

    # 前一站
    if pos == 0:
        prev = station_map.get(genome.depot_station)
    else:
        prev_task = genome.task_blocks.get(route[pos - 1])
        prev = station_map.get(prev_task.delivery_station) if prev_task else None

    # 后一站
    if pos >= len(route):
        nxt = station_map.get(genome.depot_station)
    else:
        nxt_task = genome.task_blocks.get(route[pos])
        nxt = station_map.get(nxt_task.pickup_station) if nxt_task else None

    pickup = station_map.get(task.pickup_station)
    delivery = station_map.get(task.delivery_station)

    if not all([prev, nxt, pickup, delivery]):
        return 100.0

    orig = hypot(prev.longitude - nxt.longitude, prev.latitude - nxt.latitude)
    new = (hypot(prev.longitude - pickup.longitude, prev.latitude - pickup.latitude)
           + hypot(pickup.longitude - delivery.longitude, pickup.latitude - delivery.latitude)
           + hypot(delivery.longitude - nxt.longitude, delivery.latitude - nxt.latitude))

    return max(0.0, new - orig)
