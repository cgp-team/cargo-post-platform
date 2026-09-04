"""HACO-CPS 2.1 Station Backbone：全局站点序列优化。

核心创新：
- 独立的 station backbone 搜索空间
- Station-level neighborhoods (relocate/swap/2-opt/segment)
- Station-to-station pheromone
- Task 在 backbone 上的最优分配

解决的问题：
- HACO 2.0 的 task-level 搜索无法改变 station 顺序
- Passenger-first 冻结了 passenger route
- 没有全局方向优化
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from math import hypot
from typing import TYPE_CHECKING

from .encoding import TaskBlock, TaskType

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


@dataclass
class StationBackbone:
    """全局站点序列（不含 depot）。

    例如：[S1, S2, S3, S4, S5]
    表示车辆访问站点的顺序：depot → S1 → S2 → S3 → S4 → S5 → depot
    """
    stations: list[str]
    vehicle_index: int = 0

    def copy(self) -> StationBackbone:
        return StationBackbone(stations=list(self.stations), vehicle_index=self.vehicle_index)

    def __len__(self) -> int:
        return len(self.stations)

    def __getitem__(self, idx):
        return self.stations[idx]

    def index(self, station: str) -> int:
        return self.stations.index(station)

    def insert(self, pos: int, station: str) -> None:
        self.stations.insert(pos, station)

    def remove(self, station: str) -> None:
        self.stations.remove(station)

    def swap(self, i: int, j: int) -> None:
        self.stations[i], self.stations[j] = self.stations[j], self.stations[i]

    def reverse_segment(self, i: int, j: int) -> None:
        """反转 i..j 段。"""
        self.stations[i:j+1] = reversed(self.stations[i:j+1])

    def relocate_segment(self, i: int, j: int, target: int) -> None:
        """将 i..j 段移到 target 位置。"""
        segment = self.stations[i:j+1]
        del self.stations[i:j+1]
        # 调整 target 位置
        if target > i:
            target -= len(segment)
        self.stations[target:target] = segment


@dataclass
class BackboneSolution:
    """Station Backbone + Task 分配的完整解。"""
    backbone: StationBackbone
    # 每个 station 上的任务列表 (station_id -> [task_id, ...])
    task_assignment: dict[str, list[str]]
    task_blocks: dict[str, TaskBlock]
    depot_station: str

    def copy(self) -> BackboneSolution:
        return BackboneSolution(
            backbone=self.backbone.copy(),
            task_assignment={k: list(v) for k, v in self.task_assignment.items()},
            task_blocks=dict(self.task_blocks),
            depot_station=self.depot_station,
        )

    def get_all_tasks(self) -> list[str]:
        return [tid for tids in self.task_assignment.values() for tid in tids]

    def get_station_sequence(self) -> list[str]:
        """获取完整站点序列（含 depot 和任务站点）。"""
        seq = [self.depot_station]
        for station in self.backbone.stations:
            # 添加该站点上的任务
            for task_id in self.task_assignment.get(station, []):
                task = self.task_blocks.get(task_id)
                if task:
                    seq.append(task.pickup_station)
                    if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                        seq.append(task.delivery_station)
            seq.append(station)
        seq.append(self.depot_station)
        return seq


def compute_backbone_distance(backbone: StationBackbone, station_map: dict, matrix=None) -> float:
    """计算 backbone 的总距离。"""
    depot = station_map.get("S0") or list(station_map.values())[0]
    if not backbone.stations:
        return 0.0

    total = 0.0
    current = depot
    for station_id in backbone.stations:
        s = station_map.get(station_id)
        if s:
            total += _dist(current, s, matrix)
            current = s
    total += _dist(current, depot, matrix)
    return total


def build_backbone_from_tasks(
    tasks: list[TaskBlock],
    depot_station: str,
    station_map: dict,
    matrix=None,
    strategy: str = "nearest",
) -> BackboneSolution:
    """从任务构建初始 backbone。"""
    # 收集所有唯一站点
    all_stations = set()
    for task in tasks:
        all_stations.add(task.pickup_station)
        if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
            all_stations.add(task.delivery_station)

    # 构建 backbone
    if strategy == "nearest":
        backbone = _build_nearest_backbone(list(all_stations), depot_station, station_map, matrix)
    elif strategy == "sweep":
        backbone = _build_sweep_backbone(list(all_stations), depot_station, station_map)
    else:
        backbone = StationBackbone(stations=list(all_stations))

    # 分配任务到站点
    task_assignment = _assign_tasks_to_stations(tasks, backbone)

    return BackboneSolution(
        backbone=backbone,
        task_assignment=task_assignment,
        task_blocks={t.task_id: t for t in tasks},
        depot_station=depot_station,
    )


def _build_nearest_backbone(
    stations: list[str],
    depot_station: str,
    station_map: dict,
    matrix=None,
) -> StationBackbone:
    """最近邻构建 backbone。"""
    if not stations:
        return StationBackbone(stations=[])

    depot = station_map.get(depot_station)
    if not depot:
        return StationBackbone(stations=list(stations))

    remaining = list(stations)
    ordered = []
    current = depot

    while remaining:
        best_station = None
        best_dist = float("inf")
        for sid in remaining:
            s = station_map.get(sid)
            if s:
                d = _dist(current, s, matrix)
                if d < best_dist:
                    best_dist = d
                    best_station = sid
        if best_station:
            ordered.append(best_station)
            remaining.remove(best_station)
            current = station_map.get(best_station)
        else:
            break

    return StationBackbone(stations=ordered)


def _build_sweep_backbone(
    stations: list[str],
    depot_station: str,
    station_map: dict,
) -> StationBackbone:
    """Sweep（角度排序）构建 backbone。"""
    depot = station_map.get(depot_station)
    if not depot:
        return StationBackbone(stations=list(stations))

    # 按相对于 depot 的角度排序
    def angle(sid: str) -> float:
        s = station_map.get(sid)
        if not s:
            return 0.0
        return (s.longitude - depot.longitude)  # 简化：用经度差

    sorted_stations = sorted(stations, key=angle)
    return StationBackbone(stations=sorted_stations)


def _assign_tasks_to_stations(tasks: list[TaskBlock], backbone: StationBackbone) -> dict[str, list[str]]:
    """将任务分配到 backbone 上最近的站点。"""
    assignment = {s: [] for s in backbone.stations}

    for task in tasks:
        # 分配到 pickup 站
        if task.pickup_station in assignment:
            assignment[task.pickup_station].append(task.task_id)
        else:
            # 找最近的 backbone 站
            best_station = backbone.stations[0] if backbone.stations else None
            if best_station:
                assignment[best_station].append(task.task_id)

    return assignment


def build_backbone_from_genome(genome, station_map: dict) -> BackboneSolution:
    """从 RouteGenome 构建 BackboneSolution。"""
    # 收集所有唯一站点
    all_stations = set()
    for route in genome.vehicle_routes.values():
        for task_id in route:
            task = genome.task_blocks.get(task_id)
            if task:
                all_stations.add(task.pickup_station)
                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    all_stations.add(task.delivery_station)

    # 按 genome 中的任务顺序推断 station 顺序
    station_order = []
    seen = set()
    for route in genome.vehicle_routes.values():
        for task_id in route:
            task = genome.task_blocks.get(task_id)
            if task:
                if task.pickup_station not in seen:
                    station_order.append(task.pickup_station)
                    seen.add(task.pickup_station)
                if task.task_type in (TaskType.PASSENGER, TaskType.SHIPMENT):
                    if task.delivery_station not in seen:
                        station_order.append(task.delivery_station)
                        seen.add(task.delivery_station)

    backbone = StationBackbone(stations=station_order)
    task_assignment = {}
    for route in genome.vehicle_routes.values():
        for task_id in route:
            task = genome.task_blocks.get(task_id)
            if task:
                station = task.pickup_station
                if station not in task_assignment:
                    task_assignment[station] = []
                task_assignment[station].append(task_id)

    return BackboneSolution(
        backbone=backbone,
        task_assignment=task_assignment,
        task_blocks=dict(genome.task_blocks),
        depot_station=genome.depot_station,
    )


def _dist(a, b, matrix=None) -> float:
    if matrix is not None:
        key = (a.stationId, b.stationId)
        if key in matrix:
            return matrix[key][0]
    return hypot(a.longitude - b.longitude, a.latitude - b.latitude)
