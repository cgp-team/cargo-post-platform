"""HACO-CPS 2.1 Station-level neighborhoods。

操作 station backbone 而不是 task sequence：
- Station Relocate: 移动一个站点到新位置
- Station Swap: 交换两个站点
- Station 2-opt: 反转一段站点序列
- Station Segment Relocate: 移动一段连续站点
- Station Cluster Move: 移动一组相关站点
"""

from __future__ import annotations

import random
from math import hypot
from typing import TYPE_CHECKING

from .station_backbone import BackboneSolution, StationBackbone, compute_backbone_distance

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


def station_relocate(
    solution: BackboneSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> BackboneSolution | None:
    """Station Relocate：移动一个站点到新位置。"""
    backbone = solution.backbone
    if len(backbone) < 2:
        return None

    # 选择要移动的站点
    src_idx = rng.randrange(len(backbone))
    station_id = backbone[src_idx]

    # 创建新 backbone
    new_solution = solution.copy()
    new_backbone = new_solution.backbone

    # 移除
    new_backbone.stations.pop(src_idx)

    # 找最佳新位置
    best_pos = 0
    best_dist = float("inf")

    for pos in range(len(new_backbone) + 1):
        test_backbone = new_backbone.copy()
        test_backbone.insert(pos, station_id)
        dist = compute_backbone_distance(test_backbone, station_map, matrix)
        if dist < best_dist:
            best_dist = dist
            best_pos = pos

    new_backbone.insert(best_pos, station_id)
    return new_solution


def station_swap(
    solution: BackboneSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> BackboneSolution | None:
    """Station Swap：交换两个站点的位置。"""
    backbone = solution.backbone
    if len(backbone) < 2:
        return None

    i, j = rng.sample(range(len(backbone)), 2)

    new_solution = solution.copy()
    new_solution.backbone.swap(i, j)
    return new_solution


def station_2opt(
    solution: BackboneSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> BackboneSolution | None:
    """Station 2-opt：反转一段站点序列。"""
    backbone = solution.backbone
    if len(backbone) < 2:
        return None

    i = rng.randrange(len(backbone) - 1)
    j = rng.randrange(i + 1, len(backbone))

    new_solution = solution.copy()
    new_solution.backbone.reverse_segment(i, j)
    return new_solution


def station_segment_relocate(
    solution: BackboneSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> BackboneSolution | None:
    """Station Segment Relocate：移动一段连续站点到新位置。"""
    backbone = solution.backbone
    if len(backbone) < 3:
        return None

    # 选择段
    i = rng.randrange(len(backbone) - 1)
    j = rng.randrange(i + 1, min(i + 3, len(backbone)))  # 段长 2-3

    # 如果段覆盖整个 backbone，无法 relocate
    if i == 0 and j >= len(backbone) - 1:
        return None

    # 选择目标位置（必须在段外）
    valid_targets = [t for t in range(len(backbone)) if not (i <= t <= j)]
    if not valid_targets:
        return None
    target = rng.choice(valid_targets)

    new_solution = solution.copy()
    new_solution.backbone.relocate_segment(i, j, target)
    return new_solution


def station_cluster_move(
    solution: BackboneSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
) -> BackboneSolution | None:
    """Station Cluster Move：将一组地理上接近的站点移到一起。"""
    backbone = solution.backbone
    if len(backbone) < 3:
        return None

    # 选择一个参考站点
    ref_idx = rng.randrange(len(backbone))
    ref_station = station_map.get(backbone[ref_idx])
    if not ref_station:
        return None

    # 找最近的 2-3 个站点
    distances = []
    for idx, sid in enumerate(backbone.stations):
        if idx == ref_idx:
            continue
        s = station_map.get(sid)
        if s:
            d = _dist(ref_station, s, matrix)
            distances.append((d, idx))

    distances.sort()
    cluster_indices = [ref_idx] + [idx for _, idx in distances[:2]]
    cluster_indices.sort()

    # 将这些站点移到 backbone 开头
    new_solution = solution.copy()
    cluster_stations = [backbone.stations[i] for i in cluster_indices]
    remaining = [s for i, s in enumerate(backbone.stations) if i not in cluster_indices]
    new_solution.backbone.stations = cluster_stations + remaining

    return new_solution


def apply_best_station_neighborhood(
    solution: BackboneSolution,
    station_map: dict,
    matrix: DistanceMatrix | None,
    rng: random.Random,
    max_attempts: int = 20,
) -> BackboneSolution:
    """尝试所有 station neighborhood，返回最佳改进。"""
    best = solution
    best_dist = compute_backbone_distance(solution.backbone, station_map, matrix)

    neighborhoods = [
        station_relocate,
        station_swap,
        station_2opt,
        station_segment_relocate,
        station_cluster_move,
    ]

    for _ in range(max_attempts):
        neighborhood = rng.choice(neighborhoods)
        new_solution = neighborhood(solution, station_map, matrix, rng)
        if new_solution:
            new_dist = compute_backbone_distance(new_solution.backbone, station_map, matrix)
            if new_dist < best_dist:
                best = new_solution
                best_dist = new_dist

    return best


def _dist(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)
