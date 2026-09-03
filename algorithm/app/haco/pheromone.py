"""HACO-CPS 信息素矩阵：MMAS 风格，支持上下界防止早熟收敛。"""

from __future__ import annotations

import random
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .config import HacoConfig


class PheromoneMatrix:
    """信息素矩阵 tau(task_i, task_j)。

    用于引导蚂蚁选择下一个任务的顺序。
    task_id 包括所有 TaskBlock 的 task_id 以及特殊节点 "DEPOT"。
    """

    def __init__(self, task_ids: list[str], config: HacoConfig):
        self.task_ids = list(task_ids)
        self.config = config
        self.n = len(task_ids)
        self.idx = {tid: i for i, tid in enumerate(task_ids)}

        # 初始信息素：均匀分布
        self.tau0 = 1.0
        self.tau = [[self.tau0 for _ in range(self.n)] for _ in range(self.n)]

        # MMAS 边界
        self.tau_min = config.tau_min
        self.tau_max = config.tau_max

    def get(self, i: str, j: str) -> float:
        """获取 tau(i, j)。"""
        ii = self.idx.get(i)
        jj = self.idx.get(j)
        if ii is None or jj is None:
            return self.tau0
        return self.tau[ii][jj]

    def evaporate(self) -> None:
        """信息素蒸发：tau = (1 - rho) * tau。"""
        rho = self.config.rho
        for i in range(self.n):
            for j in range(self.n):
                self.tau[i][j] *= (1 - rho)
                self.tau[i][j] = max(self.tau[i][j], self.tau_min)

    def deposit(self, task_sequence: list[str], cost: float, weight: float = 1.0) -> None:
        """信息素沉积：沿路径增加信息素。"""
        if cost <= 0:
            return
        delta = self.config.Q / cost * weight
        for k in range(len(task_sequence) - 1):
            ii = self.idx.get(task_sequence[k])
            jj = self.idx.get(task_sequence[k + 1])
            if ii is not None and jj is not None:
                self.tau[ii][jj] += delta
                self.tau[ii][jj] = min(self.tau[ii][jj], self.tau_max)

    def update_from_best(self, best_sequence: list[str], best_cost: float, elite_weight: float = 2.0) -> None:
        """精英强化：全局最优路径额外沉积。"""
        self.deposit(best_sequence, best_cost, weight=elite_weight)

    def initialize_tau0(self, initial_cost: float) -> None:
        """根据初始解成本设置 tau0。"""
        if initial_cost > 0:
            self.tau0 = 1.0 / initial_cost
        for i in range(self.n):
            for j in range(self.n):
                self.tau[i][j] = self.tau0
