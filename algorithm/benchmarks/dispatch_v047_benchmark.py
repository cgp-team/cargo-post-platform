"""DISPATCH_CORE_V047 benchmark：BASELINE_RULE / HACO_ONLY / HACO_DYNAMIC / HACO_DYNAMIC_GH。

数据来源：真实重庆站点 fixture（WGS84，来自 OSM 区域，与 `learning/training` 使用同一组
真实站点常量）。**不使用随机经纬度、不使用合成道路。**

诚实声明：
- 本机没有 OSM/GraphHopper 图与 AMAP key，因此 `HACO_DYNAMIC_GH` 若拿不到 formal geometry
  会显式记 `SKIPPED_NO_ROUTING_BACKEND`，不伪造真实道路指标。
- `HACO_ONLY` 走真实 `app.solver.solve`（HACO-CPS 1.4.1）；无 AMAP key 时矩阵口径为 degree/直线，
  报告会标注 `matrix=None`。

用法：
    python -m benchmarks.dispatch_v047_benchmark            # 写 json + 打印 markdown
"""

from __future__ import annotations

import json
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.dispatch_opt import (  # noqa: E402
    DispatchOrder,
    DispatchRequest,
    DynamicDispatchCoordinator,
    TripView,
    estimate_flexibility,
    FlexibilityInput,
)
from app.dispatch_opt.candidate_builder import CandidateType  # noqa: E402
from app.dispatch_opt.failure_cases import (  # noqa: E402
    DispatchFailureCase,
    DispatchFailureCategory,
    write_failure_cases,
)
from app.dispatch_opt.models import TripExecutionState  # noqa: E402

# 真实重庆站点（WGS84；与 learning/training 的 fixture 同源）
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
class Scenario:
    scenario_id: str
    length: str  # SHORT / MEDIUM / LONG
    flavor: str  # normal / complex / multileg / gap
    order: DispatchOrder
    current: list[TripView]
    future: list[TripView]
    other: list[TripView]


def _distance_km(a: tuple[float, float], b: tuple[float, float]) -> float:
    from math import asin, cos, radians, sin, sqrt

    r = 6371.0
    dlat = radians(b[0] - a[0])
    dlon = radians(b[1] - a[1])
    h = sin(dlat / 2) ** 2 + cos(radians(a[0])) * cos(radians(b[0])) * sin(dlon / 2) ** 2
    return 2 * r * asin(sqrt(h))


def build_scenarios() -> list[Scenario]:
    pairs = [
        ("CUPT", "JIEFANGBEI"),
        ("CTBU", "YANGJIAYU"),
        ("NANPING", "LIJIAYU"),
        ("SHAPINGBA", "CHANGAN"),
    ]
    scenarios: list[Scenario] = []
    for i, (pu, de) in enumerate(pairs):
        km = _distance_km(REAL_STATIONS[pu], REAL_STATIONS[de])
        length = "SHORT" if km < 5 else ("MEDIUM" if km < 20 else "LONG")
        o = REAL_STATIONS[pu]
        d = REAL_STATIONS[de]
        order = DispatchOrder(
            order_id=f"S{i}",
            pickup_service_point=pu,
            delivery_service_point=de,
            quantity=1,
            economic_value=40.0,
            delivery_deadline=200_000.0,
        )
        cur = TripView(
            route_id="347",
            shift_id="07:30",
            vehicle_id=1,
            departure_time=0.0,
            current_location=(o[0] - 0.01, o[1] + 0.01),
            remaining_cargo_capacity=2,
            trip_detour_remaining_m=5_000.0,
            passenger_impact_budget_s=3_600.0,
            station_coords=dict(REAL_STATIONS),
        )
        fut = TripView(
            route_id="347",
            shift_id="08:30",
            vehicle_id=2,
            departure_time=3600.0,
            current_location=(d[0] + 0.01, d[1] - 0.01),
            remaining_cargo_capacity=3,
            trip_detour_remaining_m=8_000.0,
            passenger_impact_budget_s=3_600.0,
            station_coords=dict(REAL_STATIONS),
        )
        other = TripView(
            route_id="303",
            shift_id="09:00",
            vehicle_id=3,
            departure_time=5400.0,
            current_location=(o[0] + 0.02, o[1] - 0.02),
            remaining_cargo_capacity=3,
            trip_detour_remaining_m=8_000.0,
            passenger_impact_budget_s=3_600.0,
            station_coords=dict(REAL_STATIONS),
        )
        scenarios.append(
            Scenario(f"S{i}", length, "normal", order, [cur], [fut], [other])
        )

    # 复杂：当前班已发车 + 高价值 + MultiLeg 候选
    o = REAL_STATIONS["JIEFANGBEI"]
    d = REAL_STATIONS["CTBU"]
    complex_order = DispatchOrder(
        order_id="S4",
        pickup_service_point="JIEFANGBEI",
        delivery_service_point="CTBU",
        quantity=2,
        economic_value=180.0,
        is_high_value=True,
        delivery_deadline=120_000.0,
    )
    departed = TripView(
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        departure_time=0.0,
        execution_state=TripExecutionState.DEPARTED,
        current_location=o,
        remaining_cargo_capacity=2,
        trip_detour_remaining_m=800.0,
        passenger_impact_budget_s=600.0,
        station_coords=dict(REAL_STATIONS),
    )
    leg2 = TripView(
        route_id="303",
        shift_id="08:30",
        vehicle_id=2,
        departure_time=5000.0,
        current_location=(d[0] + 0.005, d[1] - 0.005),
        remaining_cargo_capacity=3,
        trip_detour_remaining_m=1_200.0,
        passenger_impact_budget_s=3_600.0,
        station_coords=dict(REAL_STATIONS),
    )
    scenarios.append(Scenario("S4", "MEDIUM", "complex", complex_order, [departed], [leg2], [leg2]))

    # MultiLeg：单车直插超预算，两腿各自在预算内
    ml_order = DispatchOrder(
        order_id="S5",
        pickup_service_point="YANGJIAYU",
        delivery_service_point="CHANGAN",
        quantity=1,
        economic_value=90.0,
        delivery_deadline=200_000.0,
    )
    ml1 = TripView(
        route_id="347",
        shift_id="07:30",
        vehicle_id=1,
        departure_time=0.0,
        current_location=REAL_STATIONS["NANPING"],
        remaining_cargo_capacity=2,
        trip_detour_remaining_m=1_500.0,
        passenger_impact_budget_s=3_600.0,
        station_coords=dict(REAL_STATIONS),
    )
    ml2 = TripView(
        route_id="305",
        shift_id="09:30",
        vehicle_id=4,
        departure_time=6000.0,
        current_location=(REAL_STATIONS["YANGJIAYU"][0] + 0.004, REAL_STATIONS["YANGJIAYU"][1] - 0.004),
        remaining_cargo_capacity=2,
        trip_detour_remaining_m=1_500.0,
        passenger_impact_budget_s=3_600.0,
        station_coords=dict(REAL_STATIONS),
    )
    scenarios.append(Scenario("S5", "MEDIUM", "multileg", ml_order, [ml1], [ml1], [ml2]))

    # Gap：货运点偏离骨架 gap 直线
    gap_order = DispatchOrder(
        order_id="S6",
        pickup_service_point="SHAPINGBA",
        delivery_service_point="LIJIAYU",
        quantity=1,
        economic_value=60.0,
        delivery_deadline=300_000.0,
    )
    gap_cur = TripView(
        route_id="309",
        shift_id="07:00",
        vehicle_id=5,
        departure_time=0.0,
        current_location=REAL_STATIONS["CHANGAN"],
        remaining_cargo_capacity=2,
        trip_detour_remaining_m=12_000.0,
        passenger_impact_budget_s=7_200.0,
        station_coords=dict(REAL_STATIONS),
    )
    scenarios.append(Scenario("S6", "LONG", "gap", gap_order, [gap_cur], [gap_cur], []))
    return scenarios


# ── 策略 ──


def strategy_baseline_rule(sc: Scenario) -> dict:
    """BASELINE_RULE：最近车辆 / 当前班次优先，不考虑真实增量成本。"""
    trips = sc.current + sc.future + sc.other
    if not trips:
        return {"status": "HOLD", "chosen": None, "cost": 0.0}
    origin = sc.order.pickup_service_point
    target = REAL_STATIONS.get(origin)

    def key(t: TripView):
        loc = t.current_location or (0.0, 0.0)
        dist = _distance_km(loc, target) if target else 999.0
        state_rank = 0 if t.execution_state is TripExecutionState.PLANNED else 1
        return (state_rank, dist, t.departure_time)

    best = min(trips, key=key)
    return {
        "status": "ASSIGNED",
        "chosen": f"{best.route_id}/{best.shift_id}",
        "cost": round(key(best)[1], 3),
    }


def strategy_haco_dynamic(sc: Scenario, *, route_provider=None) -> dict:
    coordinator = DynamicDispatchCoordinator(route_provider=route_provider, now=0.0)
    plan = coordinator.plan(
        DispatchRequest(
            order=sc.order,
            current_trips=sc.current,
            future_trips=sc.future,
            other_route_trips=sc.other,
        )
    )
    chosen = plan.chosen
    return {
        "status": plan.status,
        "level": plan.level,
        "chosen": None if chosen is None else chosen.candidate_type.value,
        "cost": 0.0 if chosen is None else round(chosen.incremental_cost, 3),
        "reason": plan.reason_code,
        "cost_formal": bool(chosen and chosen.cost_is_formal),
        "trace": plan.trace,
    }


def run_benchmark() -> dict:
    scenarios = build_scenarios()
    failure_cases: list[DispatchFailureCase] = []
    per_strategy: dict[str, list[dict]] = {
        "BASELINE_RULE": [],
        "HACO_ONLY": [],
        "HACO_DYNAMIC": [],
        "HACO_DYNAMIC_GH": [],
    }

    for sc in scenarios:
        per_strategy["BASELINE_RULE"].append(strategy_baseline_rule(sc))

        t0 = time.perf_counter()
        dyn = strategy_haco_dynamic(sc)
        dyn["runtime_ms"] = round((time.perf_counter() - t0) * 1000, 3)
        per_strategy["HACO_DYNAMIC"].append(dyn)

        # GH 分支：本机无 OSM/GraphHopper → 显式标注，不伪造
        per_strategy["HACO_DYNAMIC_GH"].append({"status": "SKIPPED_NO_ROUTING_BACKEND"})

        if dyn["status"] == "HOLD":
            failure_cases.append(
                DispatchFailureCase(
                    case_id=f"{sc.scenario_id}_hold",
                    category=DispatchFailureCategory.UNKNOWN,
                    order_id=sc.order.order_id,
                    reason_code=dyn["reason"],
                    severity=1.0,
                    detail={"length": sc.length, "flavor": sc.flavor},
                )
            )
        if not dyn["cost_formal"]:
            failure_cases.append(
                DispatchFailureCase(
                    case_id=f"{sc.scenario_id}_nonformal_cost",
                    category=DispatchFailureCategory.ROUTING_FALLBACK,
                    order_id=sc.order.order_id,
                    reason_code="COST_NOT_FORMAL",
                    severity=2.0,
                    detail={"note": "no OSM/GraphHopper or AMAP backend on this host"},
                )
            )

    # HACO_ONLY：真实 HACO-CPS 1.4.1 批次求解（小规模、限时）
    per_strategy["HACO_ONLY"] = _run_haco_only()

    summary = _summarize(per_strategy, scenarios)
    out = {
        "version": "DISPATCH_CORE_V047",
        "scenarioCount": len(scenarios),
        "stations": REAL_STATIONS,
        "perStrategy": {
            k: [_serialize(v) for v in vals] for k, vals in per_strategy.items()
        },
        "summary": summary,
    }
    return out, failure_cases, per_strategy


def _run_haco_only() -> list[dict]:
    """真实 HACO-CPS 1.4.1 小批量求解（无 AMAP key → matrix=None，degree 口径）。"""
    try:
        from app.models import AlgorithmConfig, PlanRequest, PlanShipment, Station, Vehicle
        from app.solver import solve
    except Exception as exc:  # noqa: BLE001
        return [{"status": "SKIPPED", "reason": f"import failed: {exc}"}]

    out: list[dict] = []
    dataset = [
        ("haco-short", ["CUPT", "JIEFANGBEI"]),
        ("haco-medium", ["CTBU", "YANGJIAYU", "NANPING"]),
    ]
    for rid, names in dataset:
        stations = [
            Station(stationId=n, longitude=REAL_STATIONS[n][1], latitude=REAL_STATIONS[n][0])
            for n in names
        ]
        req = PlanRequest(
            requestId=rid,
            batchStart="2026-09-23T08:00:00+08:00",
            batchEnd="2026-09-23T20:00:00+08:00",
            depot=stations[0],
            stations=stations[1:],
            vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=10)],
            shipments=[
                PlanShipment(
                    shipmentId="P1",
                    pickupStationId=stations[0].stationId,
                    deliveryStationId=stations[1].stationId,
                    quantity=1,
                )
            ],
            algorithmConfig=AlgorithmConfig(max_iterations=8, ant_count=6),
        )
        t0 = time.perf_counter()
        outcome = solve(req, None)
        out.append(
            {
                "case": rid,
                "status": outcome.status,
                "reasonCode": outcome.reason_code,
                "algorithmVersion": outcome.algorithm_version,
                "runtime_ms": round((time.perf_counter() - t0) * 1000, 3),
                "matrix": "None(degree)",
            }
        )
    return out


def _summarize(per_strategy: dict[str, list[dict]], scenarios: list[Scenario]) -> dict:
    dyn = per_strategy["HACO_DYNAMIC"]
    assigned = sum(1 for d in dyn if d["status"] == "ASSIGNED")
    holds = sum(1 for d in dyn if d["status"] == "HOLD")
    runtimes = [d.get("runtime_ms", 0.0) for d in dyn]
    level_counts: dict[str, int] = {}
    for d in dyn:
        lvl = d.get("level", "HOLD")
        level_counts[lvl] = level_counts.get(lvl, 0) + 1
    return {
        "orders": len(scenarios),
        "hacoDynamicAssigned": assigned,
        "hacoDynamicHold": holds,
        "hacoDynamicCompletionRate": round(assigned / max(1, len(scenarios)), 3),
        "hacoDynamicP50RuntimeMs": round(statistics.median(runtimes), 3) if runtimes else 0.0,
        "hacoDynamicP95RuntimeMs": round(
            statistics.quantiles(runtimes, n=20)[-1] if len(runtimes) > 1 else (runtimes[0] if runtimes else 0.0),
            3,
        ),
        "levelCounts": level_counts,
        "formalCostAvailable": any(d.get("cost_formal") for d in dyn),
        "ghBranchSelector": "SKIPPED_NO_ROUTING_BACKEND",
    }


def _serialize(v: dict) -> dict:
    out = dict(v)
    trace = out.pop("trace", None)
    if trace is not None:
        out["traceSummary"] = trace.as_dict()["whySelected"]
    return out


def main() -> None:
    result, failure_cases, per_strategy = run_benchmark()
    data_dir = ROOT / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    (data_dir / "dispatch_v047_benchmark.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    fc_path = write_failure_cases(failure_cases, round_label="baseline")

    print("== DISPATCH_CORE_V047 benchmark ==")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print(f"failure cases written: {fc_path} (n={len(failure_cases)})")


if __name__ == "__main__":
    main()
