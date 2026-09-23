"""SearchTraceRecorder：Teacher(HACO) 搜索轨迹，0 人工标注。"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


@dataclass
class SearchTraceSample:
    search_state_id: str
    scenario_id: str
    iteration: int
    vehicle_id: int | str
    trip_id: str
    candidate_id: str
    candidate_operation: str
    candidate_type: str
    features: dict[str, float]
    feasible: bool = True
    infeasibility: float = 0.0
    vehicle_count: int = 0
    passenger_impact: float = 0.0
    cargo_detour: float = 0.0
    total_distance: float = 0.0
    total_duration: float = 0.0
    objective_rank: int = 0
    accepted: bool = False
    became_best: bool = False
    infeasibility_reason: str | None = None
    route_provider: str = "local_graph"
    route_version: str = ""
    graph_version: str = ""
    timestamp: float = field(default_factory=time.time)
    random_seed: int = 0
    leakage_guard: str = "features=decision_time_only"


FEATURE_NAMES: tuple[str, ...] = (
    "passengerCapacity", "cargoCapacity", "remainingCargoCapacity",
    "initialPassengerLoad", "initialCargoLoad", "tripProgress", "tripLocked",
    "remainingTripDistance", "remainingTripDuration",
    "orderTypeCode", "cargoWeight", "cargoVolume", "itemCount",
    "pickupDistance", "deliveryDistance", "priority", "economicValue",
    "highValue", "slaRemaining",
    "gapIndex", "gapDistance", "gapDuration", "detourDistance", "detourDuration",
    "passengerImpact", "remainingDetourBudget", "nextMandatoryStationDistance",
    "roadDistance", "roadDuration", "straightDistanceLowerBound",
    "roadDetourRatio", "routePointCount", "localRoadFlag", "cachedRouteFlag",
    "amapVerifiedFlag", "routeConfidence",
    "gpsFreshness", "passedStation", "currentTime",
    "sameRoute", "sameDirection", "multiLegPossible", "handoverDistance",
    "handoverTime", "servicePointTypeCode", "reachabilityReasonCode",
    "historicalSuccessCount", "historicalFailureCount", "historicalUseCount",
    "historicalAverageDetour", "historicalAverageDuration", "historicalAcceptedRatio",
)


class SearchTraceRecorder:
    def __init__(self, graph_version: str = "", route_version: str = ""):
        self.graph_version = graph_version
        self.route_version = route_version
        self.samples: list[SearchTraceSample] = []
        self._state_seq = 0

    def begin_state(
        self, scenario_id: str, iteration: int,
        vehicle_id: int | str, trip_id: str, random_seed: int = 0,
    ) -> str:
        self._state_seq += 1
        return f"{scenario_id}:s{self._state_seq:05d}"

    def record(
        self, *, search_state_id: str, scenario_id: str, iteration: int,
        vehicle_id: int | str, trip_id: str, candidate_id: str,
        candidate_operation: str, candidate_type: str,
        features: dict[str, float], objective: Any | None = None,
        feasible: bool = True, infeasibility_reason: str | None = None,
        accepted: bool = False, became_best: bool = False,
        random_seed: int = 0, route_provider: str = "local_graph",
    ) -> SearchTraceSample:
        feats = {k: float(features.get(k, 0.0)) for k in FEATURE_NAMES}
        sample = SearchTraceSample(
            search_state_id=search_state_id,
            scenario_id=scenario_id,
            iteration=iteration,
            vehicle_id=vehicle_id,
            trip_id=trip_id,
            candidate_id=candidate_id,
            candidate_operation=candidate_operation,
            candidate_type=candidate_type,
            features=feats,
            feasible=feasible,
            infeasibility=float(getattr(objective, "infeasibility", 0.0) if objective else 0.0),
            vehicle_count=int(getattr(objective, "vehicle_count", 0) if objective else 0),
            passenger_impact=float(getattr(objective, "passenger_impact", 0.0) if objective else 0.0),
            cargo_detour=float(getattr(objective, "cargo_detour", 0.0) if objective else 0.0),
            total_distance=float(getattr(objective, "total_distance", 0.0) if objective else 0.0),
            total_duration=float(getattr(objective, "total_duration", 0.0) if objective else 0.0),
            accepted=accepted, became_best=became_best,
            infeasibility_reason=infeasibility_reason,
            route_provider=route_provider,
            route_version=self.route_version,
            graph_version=self.graph_version,
            random_seed=random_seed,
        )
        self.samples.append(sample)
        return sample

    def assign_objective_ranks(self) -> None:
        """按 ObjectiveVector.key() 语义给 group 内候选排名（1=最好）。"""
        from collections import defaultdict
        groups: dict[str, list[SearchTraceSample]] = defaultdict(list)
        for s in self.samples:
            groups[s.search_state_id].append(s)
        for _sid, items in groups.items():
            items.sort(key=lambda s: (
                s.infeasibility, s.vehicle_count,
                round(s.passenger_impact, 3), round(s.cargo_detour, 3),
                round(s.total_distance, 3), round(s.total_duration, 1),
            ))
            for i, s in enumerate(items, 1):
                s.objective_rank = i

    def save_jsonl(self, path: str | Path) -> int:
        p = Path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        with p.open("w", encoding="utf-8") as f:
            for s in self.samples:
                f.write(json.dumps(asdict(s), ensure_ascii=False) + "\n")
        return len(self.samples)

    @staticmethod
    def load_jsonl(path: str | Path) -> list[SearchTraceSample]:
        out: list[SearchTraceSample] = []
        with Path(path).open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    out.append(SearchTraceSample(**json.loads(line)))
        return out
