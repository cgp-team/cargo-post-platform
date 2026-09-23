"""ObjectiveVector 敏感性分析（DISPATCH_CORE_V047 section 24）。

比较三种排序口径：
  A：当前 lexicographic（ObjectiveVector.key）
  B：SLA 违规优先后再优化
  C：passenger-safe 优先（先乘客影响，再车辆数）

重点寻找反例：低车辆数但高 passenger impact / 低距离但高 SLA violation。

数据：真实重庆站点 fixture + 真实 HACO-CPS 1.4.1 求解（多 seed 产生备选解）。
SLA violation 目前**没有真实乘客时刻表**，用 `max(0, total_duration - time_limit)` 作为
显式标注的 PROXY（报告中标注 PROXY，不伪装成真实延误）。
"""

from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.haco.encoding import ObjectiveVector  # noqa: E402
from app.models import AlgorithmConfig, PlanRequest, PlanShipment, Station, Vehicle  # noqa: E402
from app.objective_compare import evaluate_solution_objective  # noqa: E402
from app.solver import solve  # noqa: E402

REAL_STATIONS: dict[str, tuple[float, float]] = {
    "CUPT": (29.5333, 106.6074),
    "CTBU": (29.5020, 106.5830),
    "NANPING": (29.5220, 106.5680),
    "SHAPINGBA": (29.5400, 106.4500),
    "YANGJIAYU": (29.5600, 106.5700),
    "JIEFANGBEI": (29.5630, 106.5750),
    "CHANGAN": (29.5280, 106.5500),
    "LIJIAYU": (29.5450, 106.5300),
}


@dataclass
class Alternative:
    case: str
    seed: int
    vehicle_count: int
    passenger_impact: float
    cargo_detour: float
    total_distance: float
    total_duration: float
    sla_violation_s: float  # PROXY

    def key_a(self):
        return (self.vehicle_count, self.passenger_impact, self.cargo_detour, self.total_distance, self.total_duration)

    def key_b(self):
        # SLA 优先（PROXY 违规量），其后沿用 lexicographic
        return (self.sla_violation_s, self.vehicle_count, self.passenger_impact, self.cargo_detour, self.total_distance)

    def key_c(self):
        # passenger-safe：先乘客影响，再车辆数
        return (self.passenger_impact, self.vehicle_count, self.cargo_detour, self.total_distance, self.total_duration)

    def as_dict(self) -> dict:
        return {
            "case": self.case,
            "seed": self.seed,
            "vehicleCount": self.vehicle_count,
            "passengerImpact": round(self.passenger_impact, 3),
            "cargoDetour": round(self.cargo_detour, 3),
            "totalDistance": round(self.total_distance, 3),
            "totalDuration": round(self.total_duration, 3),
            "slaViolationProxyS": round(self.sla_violation_s, 3),
            "keyA": self.key_a(),
            "keyB": self.key_b(),
            "keyC": self.key_c(),
        }


CASES = [
    ("sens-short", ["CUPT", "JIEFANGBEI"], 2, ["P1"]),
    ("sens-medium", ["CTBU", "YANGJIAYU", "NANPING"], 2, ["P1", "P2"]),
    ("sens-multi", ["CHANGAN", "LIJIAYU", "SHAPINGBA", "CUPT"], 3, ["P1", "P2", "P3"]),
]

SEEDS = [20260903, 20260911, 20260921]


def _run_case(case: str, names: list[str], n_vehicles: int, shipment_prefix: list[str]):
    stations = [
        Station(stationId=n, longitude=REAL_STATIONS[n][1], latitude=REAL_STATIONS[n][0]) for n in names
    ]
    shipments = []
    for i, sid in enumerate(shipment_prefix):
        pickup = stations[i % (len(stations) - 1)]
        delivery = stations[(i + 1) % len(stations)]
        shipments.append(
            PlanShipment(
                shipmentId=sid,
                pickupStationId=pickup.stationId,
                deliveryStationId=delivery.stationId,
                quantity=1 + (i % 2),
            )
        )

    alts: list[Alternative] = []
    for seed in SEEDS:
        req = PlanRequest(
            requestId=f"{case}-{seed}",
            batchStart="2026-09-23T08:00:00+08:00",
            batchEnd="2026-09-23T12:00:00+08:00",  # 4h 窗口
            depot=stations[0],
            stations=stations[1:],
            vehicles=[
                Vehicle(vehicleId=v + 1, passengerCapacity=5, cargoCapacity=8, initialPassengerLoad=1 if v == 0 else 0)
                for v in range(n_vehicles)
            ],
            shipments=shipments,
            algorithmConfig=AlgorithmConfig(randomSeed=seed, max_iterations=10, ant_count=8),
        )
        outcome = solve(req, None)
        if outcome.status != "feasible":
            continue
        obj: ObjectiveVector = evaluate_solution_objective(outcome.vehicle_plans)
        time_limit_s = 4 * 3600.0
        alts.append(
            Alternative(
                case=case,
                seed=seed,
                vehicle_count=obj.vehicle_count,
                passenger_impact=obj.passenger_impact,
                cargo_detour=obj.cargo_detour,
                total_distance=obj.total_distance,
                total_duration=obj.total_duration,
                sla_violation_s=max(0.0, obj.total_duration - time_limit_s),
            )
        )
    return alts


def analyze() -> dict:
    all_alts: list[Alternative] = []
    for case, names, nveh, prefix in CASES:
        all_alts.extend(_run_case(case, names, nveh, prefix))

    by_case: dict[str, list[Alternative]] = {}
    for a in all_alts:
        by_case.setdefault(a.case, []).append(a)

    disagreements = []
    counterexamples = []
    for case, alts in by_case.items():
        if len(alts) < 2:
            continue
        best_a = min(alts, key=lambda x: x.key_a())
        best_b = min(alts, key=lambda x: x.key_b())
        best_c = min(alts, key=lambda x: x.key_c())
        if not (best_a.seed == best_b.seed == best_c.seed):
            disagreements.append(
                {
                    "case": case,
                    "A": best_a.as_dict(),
                    "B": best_b.as_dict(),
                    "C": best_c.as_dict(),
                }
            )
        # 反例 1：A 选更少车辆但明显更高 passenger impact
        for other in alts:
            if (
                best_a.vehicle_count <= other.vehicle_count
                and best_a.passenger_impact > other.passenger_impact + 1e-6
            ):
                counterexamples.append(
                    {
                        "type": "LOW_VEHICLE_HIGH_PASSENGER_IMPACT",
                        "case": case,
                        "pickedByA": best_a.as_dict(),
                        "alternative": other.as_dict(),
                    }
                )
                break
        # 反例 2：A 距离不差但 SLA proxy 违规更高
        for other in alts:
            if (
                best_a.total_distance <= other.total_distance
                and best_a.sla_violation_s > other.sla_violation_s + 1e-6
            ):
                counterexamples.append(
                    {
                        "type": "LOW_DISTANCE_HIGH_SLA_VIOLATION",
                        "case": case,
                        "pickedByA": best_a.as_dict(),
                        "alternative": other.as_dict(),
                    }
                )
                break

    return {
        "version": "DISPATCH_CORE_V047",
        "notes": [
            "SLA violation 为 PROXY：max(0, total_duration - batch window)，非真实乘客时刻表延误。",
            "无 AMAP key：矩阵口径为 degree/直线（matrix=None）。",
        ],
        "cases": {k: [a.as_dict() for a in v] for k, v in by_case.items()},
        "disagreements": disagreements,
        "counterexamples": counterexamples,
        "recommendation": (
            "在当前 benchmark 上："
            + ("存在口径分歧，建议维持 A 但补充 passenger-safe 二级排序。" if disagreements else "A/B/C 排序一致，维持当前 lexicographic 即可。")
        ),
    }


def main() -> None:
    result = analyze()
    out = ROOT / "data" / "dispatch_v047_objective_sensitivity.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "cases"}, ensure_ascii=False, indent=2))
    print("written:", out)


if __name__ == "__main__":
    main()
