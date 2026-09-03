"""HACO-CPS 1.4.0 Elite Archive：精英解存档。

功能：
- 维护多样化精英解集合
- 支持采样（70% 最优附近，30% 多样化）
- 自动淘汰劣解
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from .encoding import ObjectiveVector
from .route_genome import RouteGenome

if TYPE_CHECKING:
    from .encoding import TaskBlock


@dataclass
class ArchiveEntry:
    routes: list[RouteGenome]
    objective: ObjectiveVector
    signature: str


class EliteArchive:
    """精英解存档。"""

    def __init__(self, max_size: int = 10):
        self.max_size = max_size
        self.entries: list[ArchiveEntry] = []

    def add(
        self,
        routes: list[RouteGenome],
        objective: ObjectiveVector,
        tasks_by_id: dict[str, TaskBlock],
        station_map: dict,
        matrix,
    ) -> bool:
        """添加解到存档。返回是否成功添加。"""
        signature = self._compute_signature(routes)

        # 检查是否已存在
        for entry in self.entries:
            if entry.signature == signature:
                return False

        # 添加
        entry = ArchiveEntry(
            routes=[r.copy() for r in routes],
            objective=objective,
            signature=signature,
        )
        self.entries.append(entry)

        # 淘汰劣解
        if len(self.entries) > self.max_size:
            self._prune()

        return True

    def sample_elite(self, rng) -> list[RouteGenome] | None:
        """采样精英解。

        70% 最优附近，30% 多样化精英。
        """
        if not self.entries:
            return None

        if rng.random() < 0.7:
            # 选择最优
            entry = min(self.entries, key=lambda x: x.objective)
        else:
            # 随机选择
            entry = rng.choice(self.entries)

        return [r.copy() for r in entry.routes]

    def get_best(self) -> ArchiveEntry | None:
        """获取最优解。"""
        if not self.entries:
            return None
        return min(self.entries, key=lambda x: x.objective)

    def _prune(self):
        """淘汰劣解，保留 max_size 个。"""
        # 按目标排序
        self.entries.sort(key=lambda x: x.objective)
        self.entries = self.entries[:self.max_size]

    def _compute_signature(self, routes: list[RouteGenome]) -> str:
        """计算解签名。"""
        parts = []
        for route in routes:
            if route.task_count() == 0:
                continue
            task_ids = sorted(
                e.task_id for e in route.events if e.task_id
            )
            parts.append(
                f"V{route.vehicle_index}:{'|'.join(task_ids)}"
            )
        return ";".join(sorted(parts))
