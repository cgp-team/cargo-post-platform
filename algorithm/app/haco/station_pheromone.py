"""HACO-CPS 2.1 Station-to-Station 信息素。

引导 station backbone 的搜索方向。
tau_station[S1][S2] 表示从 S1 到 S2 的信息素水平。
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .config import HacoConfig


class StationPheromone:
    """Station-to-Station 信息素矩阵。"""

    def __init__(self, station_ids: list[str], config: HacoConfig):
        self.station_ids = list(station_ids)
        self.config = config
        self.n = len(station_ids)
        self.idx = {sid: i for i, sid in enumerate(station_ids)}

        # 初始信息素
        self.tau0 = 1.0
        self.tau = [[self.tau0 for _ in range(self.n)] for _ in range(self.n)]

        # MMAS 边界
        self.tau_min = config.tau_min
        self.tau_max = config.tau_max

    def get(self, i: str, j: str) -> float:
        ii = self.idx.get(i)
        jj = self.idx.get(j)
        if ii is None or jj is None:
            return self.tau0
        return self.tau[ii][jj]

    def evaporate(self) -> None:
        rho = self.config.rho
        for i in range(self.n):
            for j in range(self.n):
                self.tau[i][j] *= (1 - rho)
                self.tau[i][j] = max(self.tau[i][j], self.tau_min)

    def deposit(self, station_sequence: list[str], cost: float, weight: float = 1.0) -> None:
        """沿站点序列沉积信息素。"""
        if cost <= 0:
            return
        delta = self.config.Q / cost * weight
        for k in range(len(station_sequence) - 1):
            ii = self.idx.get(station_sequence[k])
            jj = self.idx.get(station_sequence[k + 1])
            if ii is not None and jj is not None:
                self.tau[ii][jj] += delta
                self.tau[ii][jj] = min(self.tau[ii][jj], self.tau_max)

    def deposit_best(self, station_sequence: list[str], cost: float, elite_weight: float = 2.0) -> None:
        """精英强化。"""
        self.deposit(station_sequence, cost, weight=elite_weight)

    def initialize_tau0(self, initial_cost: float) -> None:
        if initial_cost > 0:
            self.tau0 = 1.0 / initial_cost
        for i in range(self.n):
            for j in range(self.n):
                self.tau[i][j] = self.tau0

    def restart(self, ratio: float = 0.5) -> None:
        """部分重启。"""
        for i in range(self.n):
            for j in range(self.n):
                self.tau[i][j] = self.tau0 * ratio + self.tau[i][j] * (1 - ratio)
