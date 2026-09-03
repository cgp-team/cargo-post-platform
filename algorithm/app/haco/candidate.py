"""HACO-CPS 候选插入模型：CandidateInsertion。

每个候选插入描述：
- 哪个任务
- 插入到哪辆车的哪个 gap
- 增量成本（距离、时间、乘客影响等）
- 启发式得分
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .encoding import TaskBlock
    from .route_state import RouteState


@dataclass
class CandidateInsertion:
    """候选插入方案。"""
    task_id: str
    task: TaskBlock
    vehicle_index: int
    gap_index: int
    delta_distance: float      # 距离增量 (km)
    delta_duration: float      # 时间增量 (s)
    passenger_impact: float    # 乘客影响 (s)
    cargo_detour: float        # 货物绕行 (km)
    heuristic_score: float     # 启发式得分 (越小越好)
    feasible: bool = True
    reject_reason: str | None = None

    def __lt__(self, other: CandidateInsertion) -> bool:
        """比较候选插入（得分越小越好）。"""
        return self.heuristic_score < other.heuristic_score
