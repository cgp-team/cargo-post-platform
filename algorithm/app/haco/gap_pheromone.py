"""HACO-CPS 1.2.0 Task-to-Gap 信息素矩阵。

核心创新：引导任务分配到合适的骨架间隙。
tau(task_id, gap_index) 表示任务 i 插入到 gap j 的信息素水平。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .config import HacoConfig


class GapPheromone:
    """Task-to-Gap 信息素矩阵。

    tau(task_id, gap_index) 引导任务分配到合适的骨架间隙。
    """

    def __init__(self, task_ids: list[str], gap_count: int, config: HacoConfig):
        self.task_ids = list(task_ids)
        self.gap_count = gap_count
        self.config = config

        # 初始信息素：均匀分布
        self.tau0 = 1.0
        self.tau: dict[tuple[str, int], float] = {}
        for tid in task_ids:
            for g in range(gap_count):
                self.tau[(tid, g)] = self.tau0

        # MMAS 边界
        self.tau_min = config.tau_min
        self.tau_max = config.tau_max

    def get(self, task_id: str, gap_index: int) -> float:
        """获取 tau(task_id, gap_index)。"""
        return self.tau.get((task_id, gap_index), self.tau0)

    def evaporate(self) -> None:
        """信息素蒸发。"""
        rho = self.config.rho
        for key in self.tau:
            self.tau[key] *= (1 - rho)
            self.tau[key] = max(self.tau[key], self.tau_min)

    def deposit(self, task_gap_assignments: dict[str, int], cost: float, weight: float = 1.0) -> None:
        """信息素沉积：对 (task, gap) 对增加信息素。"""
        if cost <= 0:
            return
        delta = self.config.Q / cost * weight
        for task_id, gap_index in task_gap_assignments.items():
            key = (task_id, gap_index)
            if key in self.tau:
                self.tau[key] += delta
                self.tau[key] = min(self.tau[key], self.tau_max)

    def update_from_best(self, assignments: dict[str, int], cost: float, elite_weight: float = 2.0) -> None:
        """精英强化。"""
        self.deposit(assignments, cost, weight=elite_weight)

    def initialize_tau0(self, initial_cost: float) -> None:
        """根据初始解成本设置 tau0。"""
        if initial_cost > 0:
            self.tau0 = 1.0 / initial_cost
        for key in self.tau:
            self.tau[key] = self.tau0

    def restart(self, ratio: float = 0.5, elite_keys: set | None = None) -> None:
        """部分重启（防早熟收敛）。"""
        for key in self.tau:
            if elite_keys and key in elite_keys:
                continue  # 保留精英边
            self.tau[key] = self.tau0 * ratio + self.tau[key] * (1 - ratio)
