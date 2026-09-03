"""HACO-CPS 1.2.0 精英存档：多样性管理 + 质量-多样性平衡。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from .encoding import ObjectiveVector
from .route_state import RouteState

if TYPE_CHECKING:
    from ..distance import DistanceMatrix
    from ..models import Station


@dataclass
class ArchiveEntry:
    """存档条目。"""
    states: list[RouteState]
    objective: ObjectiveVector
    signature: str
    diversity_score: float = 0.0


class EliteArchive:
    """精英存档：保存高质量且多样化的解。

    选择策略：质量 + 多样性共同决定。
    """

    def __init__(self, max_size: int = 10):
        self.max_size = max_size
        self.entries: list[ArchiveEntry] = []

    def add(self, states: list[RouteState], objective: ObjectiveVector, signature: str) -> bool:
        """添加解到存档。返回是否成功添加。"""
        # 检查是否已存在相同签名
        for entry in self.entries:
            if entry.signature == signature:
                # 如果更好，替换
                if objective < entry.objective:
                    entry.states = [s.copy() for s in states]
                    entry.objective = objective
                    return True
                return False

        # 计算多样性得分
        diversity = self._compute_diversity_score(signature)

        entry = ArchiveEntry(
            states=[s.copy() for s in states],
            objective=objective,
            signature=signature,
            diversity_score=diversity,
        )

        if len(self.entries) < self.max_size:
            self.entries.append(entry)
            self._update_diversity_scores()
            return True

        # 替换最差的（质量 + 多样性）
        worst_idx = self._find_worst_index()
        if self._is_better_than_worst(entry, worst_idx):
            self.entries[worst_idx] = entry
            self._update_diversity_scores()
            return True

        return False

    def get_best(self) -> ArchiveEntry | None:
        """获取最佳解。"""
        if not self.entries:
            return None
        return min(self.entries, key=lambda e: e.objective)

    def get_diverse_elite(self, count: int) -> list[ArchiveEntry]:
        """获取多样化精英子集。"""
        if not self.entries:
            return []

        # 按质量排序
        by_quality = sorted(self.entries, key=lambda e: e.objective)

        # 选择 top quality + 高多样性
        selected = [by_quality[0]]  # 总是包含最佳
        remaining = by_quality[1:]

        while len(selected) < count and remaining:
            # 选择与已选最不相似的
            best_candidate = None
            best_diversity = -1
            for candidate in remaining:
                min_sim = min(
                    _route_similarity(candidate.signature, s.signature)
                    for s in selected
                )
                if min_sim > best_diversity:
                    best_diversity = min_sim
                    best_candidate = candidate
            if best_candidate:
                selected.append(best_candidate)
                remaining.remove(best_candidate)
            else:
                break

        return selected

    def _compute_diversity_score(self, signature: str) -> float:
        """计算解的多样性得分（与存档中其他解的平均距离）。"""
        if not self.entries:
            return 1.0
        similarities = [_route_similarity(signature, e.signature) for e in self.entries]
        return 1.0 - (sum(similarities) / len(similarities)) if similarities else 1.0

    def _update_diversity_scores(self) -> None:
        """更新所有条目的多样性得分。"""
        for entry in self.entries:
            entry.diversity_score = self._compute_diversity_score(entry.signature)

    def _find_worst_index(self) -> int:
        """找到最差条目的索引（质量 + 多样性）。"""
        def combined_score(entry: ArchiveEntry) -> float:
            # 质量归一化 + 多样性
            return entry.objective.normalized_cost() - entry.diversity_score * 100

        return max(range(len(self.entries)), key=lambda i: combined_score(self.entries[i]))

    def _is_better_than_worst(self, entry: ArchiveEntry, worst_idx: int) -> bool:
        """检查新条目是否比最差条目更好。"""
        worst = self.entries[worst_idx]
        # 质量更好，或者质量相当但多样性更高
        if entry.objective < worst.objective:
            return True
        if entry.objective.normalized_cost() < worst.objective.normalized_cost() * 1.1:
            return entry.diversity_score > worst.diversity_score
        return False


def _route_similarity(sig_a: str, sig_b: str) -> float:
    """计算两个路线签名的相似度（0=完全不同，1=完全相同）。"""
    if sig_a == sig_b:
        return 1.0

    # 基于 edge 差异计算
    edges_a = _extract_edges(sig_a)
    edges_b = _extract_edges(sig_b)

    if not edges_a and not edges_b:
        return 1.0
    if not edges_a or not edges_b:
        return 0.0

    common = edges_a & edges_b
    total = edges_a | edges_b
    return len(common) / len(total) if total else 0.0


def _extract_edges(signature: str) -> set[tuple[str, str]]:
    """从签名中提取边集合。"""
    edges = set()
    parts = signature.split(";")
    for part in parts:
        if ":" not in part:
            continue
        _, tasks_str = part.split(":", 1)
        task_ids = tasks_str.split("|")
        for i in range(len(task_ids) - 1):
            edges.add((task_ids[i], task_ids[i + 1]))
    return edges


def compute_route_signature(states: list[RouteState]) -> str:
    """计算解的签名。"""
    parts = []
    for state in states:
        if not state.tasks:
            continue
        task_ids = sorted(t.task_id for t in state.tasks)
        parts.append(f"V{state.vehicle_index}:{'|'.join(task_ids)}")
    return ";".join(sorted(parts))
