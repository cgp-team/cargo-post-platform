"""公交通行预检结果（`PlanOrder.roadAccess`）必须改变算法的停靠决策。

契约出处（`app/models.py:93-97`，原文口径）：
  "BLOCKED"      —— 该点位只能经巷道/窄路到达，公交车（大型车）进不去 →
                    算法不得安排绕行上门，改为最近站点交接
  "NEAR_STATION" —— 该点位距最近站点 ≤100 m，顺路在站点交接即可，不必绕进巷道

现状（写这份用例时已核实）：`app/baseline/ortools_solver.py:144-146、600-625` 读这个字段
（原因码 `ROAD_NOT_BUS_ACCESSIBLE` / `NEAR_STATION_WITHIN_100M`），
而 `app/haco/` 内 grep `roadAccess|road_access` 命中 **0** ——
也就是说切到 HACO 之后，业务侧辛苦算出来的「公交车根本进不去」被算法当作不存在，
照样把停靠排在那个点上，后端拿到的是一份车到不了的路单。

与绕行超阈值的关系：两者走同一套三级处置（在车服务 → 就近站点交接 → 舍弃交多段联运），
但**触发条件不同**：绕行超阈值是「不划算」，不可通行是「去不了」。
所以不可通行的点位即使在绕行预算内（本用例特意取 600 m 偏移，绕行≈1.2 km < 2 km 阈值），
也必须改站点交接 —— 这才是这条契约的意义。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.distance import haversine_km
from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    PlanShipment,
    Station,
    StopAction,
    Vehicle,
)
from app.solver import solve

from benchmarks.lab.check import check_plan

LAT0, LON0 = 29.55, 106.50
KM_PER_DEG_LAT = 110.6
M_PER_DEG_LAT = 110_600.0
BASE = datetime(2026, 10, 8, 8, 0, tzinfo=timezone.utc)

SKELETON = ["S1", "S2", "S3"]


def _st(sid: str, km: float, lon_off_km: float = 0.0) -> Station:
    return Station(stationId=sid, longitude=round(LON0 + lon_off_km / 105.0, 6),
                   latitude=round(LAT0 + km / KM_PER_DEG_LAT, 6))


def _off_s2(sid: str, off_m: float) -> Station:
    """在 S2 旁边 off_m 米处开一个点（沿经线方向偏移，真实巷道/支路的几何形态）。"""
    return Station(stationId=sid, longitude=round(LON0 + 0.0002, 6),
                   latitude=round(LAT0 + 6.0 / KM_PER_DEG_LAT + off_m / M_PER_DEG_LAT, 6))


def _matrix(stations):
    m = {}
    for a in stations:
        for b in stations:
            if a.stationId == b.stationId:
                m[(a.stationId, b.stationId)] = (0.0, 0.0)
            else:
                km = haversine_km(a.longitude, a.latitude, b.longitude, b.latitude)
                m[(a.stationId, b.stationId)] = (round(km, 4), round(km / 25 * 3600, 1))
    return m


def _request(stations, orders, shipments=None):
    return PlanRequest(
        requestId="road-access-case", batchStart=BASE, batchEnd=BASE + timedelta(hours=6),
        depot=stations[0], stations=stations,
        vehicles=[Vehicle(vehicleId=1000, passengerCapacity=8, cargoCapacity=20,
                          skeleton=list(SKELETON))],
        orders=orders, shipments=shipments or [],
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=5),
    )


def _stops_for(out, order_id):
    return [(p.vehicleId, s) for p in out.vehicle_plans for s in p.stops
            if s.orderId == order_id]


def _served(out):
    return {s.orderId for p in out.vehicle_plans for s in p.stops if s.orderId}


# 骨架站集合（交接只允许发生在这些点上）
def _at_skeleton(out, order_id) -> bool:
    return all(s.stationId in SKELETON for _, s in _stops_for(out, order_id))


def _door_visit(out, order_id, door_station: str) -> bool:
    return any(s.stationId == door_station for _, s in _stops_for(out, order_id))


# ─── 1. BLOCKED：公交进不去的点位，即使在绕行预算内也不许停车 ─────────

def test_blocked_point_is_never_a_bus_stop():
    """V1 距 S2 只有 600 m（绕行≈1.2km，在 2km 预算内），但 roadAccess=BLOCKED。

    「不绕路」不等于「进得去」：算法不能因为绕行便宜就把车开进公交进不了的巷道。
    """
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0), _off_s2("V1", 600.0)]
    m = _matrix(stations)
    orders = [PlanOrder(orderId="K1", orderType=OrderType.PICKUP, stationId="V1",
                        itemCount=1, roadAccess="BLOCKED")]
    out = solve(_request(stations, orders), m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status != "feasible":
        pytest.fail(f"一个 600m 外的揽收点（可站点交接）不该让整批无解：{out.reason_code}")
    if "K1" not in _served(out):
        assert "K1" in set(out.unassigned_order_ids or []), "既没服务又没申报"
        return
    assert not _door_visit(out, "K1", "V1"), \
        "公交进不去的点位仍被排成停靠点（后端会拿到去不了的路单）"
    assert _at_skeleton(out, "K1"), "改站点交接时必须落在骨架站上"
    stop = _stops_for(out, "K1")[0][1]
    assert stop.serviceMode == "NEAREST_STATION", stop
    # 项目既定口径（与 baseline / check.py 一致）：servicePoint = 服务实际发生的点，
    # 也就是车真正停的那一站；客户真实地址留在订单记录里，不在这里重复。
    assert stop.servicePoint == stop.stationId, stop
    assert stop.reasonCode == "ROAD_NOT_BUS_ACCESSIBLE", \
        f"原因码要与「绕行不划算」区分开：{stop.reasonCode}"


# ─── 2. NEAR_STATION：站点就在旁边，不必绕进巷 ────────────────────

def test_near_station_point_is_served_at_the_station():
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0), _off_s2("V2", 80.0)]
    m = _matrix(stations)
    orders = [PlanOrder(orderId="K2", orderType=OrderType.PICKUP, stationId="V2",
                        itemCount=1, roadAccess="NEAR_STATION")]
    out = solve(_request(stations, orders), m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    if out.status != "feasible" or "K2" not in _served(out):
        pytest.fail(f"距站 80m 的点本该顺路在站点交接，结果 status={out.status} "
                    f"served={_served(out)} {out.warnings}")
    assert not _door_visit(out, "K2", "V2"), "站点在旁边还绕进去"
    assert _at_skeleton(out, "K2")
    assert _stops_for(out, "K2")[0][1].reasonCode == "NEAR_STATION_WITHIN_100M"


# ─── 3. 缺省（未做预检）行为不得改变 ─────────────────────────────

def test_unchecked_point_is_still_servable_at_its_door():
    """正向对照：roadAccess 缺省 None = 没做预检 → 一切照旧，在这个点位上门揽收。

    没有这一条，前两条可以靠「所有揽收点一律改站点交接 / 一律舍弃」骗绿。
    """
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0), _off_s2("V3", 600.0)]
    m = _matrix(stations)
    orders = [PlanOrder(orderId="K3", orderType=OrderType.PICKUP, stationId="V3",
                        itemCount=1)]
    out = solve(_request(stations, orders), m)
    assert out.status == "feasible", out.reason_code
    assert "K3" in _served(out)
    assert _door_visit(out, "K3", "V3"), \
        "未做通行预检的点位被误当成不可通行（不该凭空加限制）"


def test_blocked_and_near_station_are_the_only_rewrites():
    """混合批次：一条 BLOCKED、一条正常 —— 只有 BLOCKED 那条被改写，正常那条照旧上门。"""
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0),
                _off_s2("V4", 600.0), _off_s2("V5", 700.0)]
    m = _matrix(stations)
    orders = [
        PlanOrder(orderId="K4", orderType=OrderType.PICKUP, stationId="V4",
                  itemCount=1, roadAccess="BLOCKED"),
        PlanOrder(orderId="K5", orderType=OrderType.PICKUP, stationId="V5", itemCount=1),
    ]
    out = solve(_request(stations, orders), m)
    assert out.status == "feasible", out.reason_code
    if "K4" in _served(out):
        assert not _door_visit(out, "K4", "V4")
        assert _at_skeleton(out, "K4")
    if "K5" in _served(out):
        assert _door_visit(out, "K5", "V5"), "正常订单被连带改成了站点交接"


# ─── 4. 乘客不受通行预检影响（每站必停拉客）────────────────────────

def test_passengers_are_never_handovers():
    """乘客的上落点本来就是骨架站（每站必停），任何情况下不该被标成站点交接或舍弃。"""
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0), _off_s2("V6", 600.0)]
    m = _matrix(stations)
    orders = [
        PlanOrder(orderId="P1", orderType=OrderType.PASSENGER,
                  boardingStationId="S1", alightingStationId="S3"),
        PlanOrder(orderId="K6", orderType=OrderType.PICKUP, stationId="V6",
                  itemCount=1, roadAccess="BLOCKED"),
    ]
    out = solve(_request(stations, orders), m)
    assert out.status == "feasible", out.reason_code
    assert "P1" in _served(out), "乘客因货运通行预检被舍弃"
    for _, s in _stops_for(out, "P1"):
        assert s.serviceMode in (None, ""), f"乘客停靠被贴了交接标签：{s.serviceMode}"
        assert s.accepted is True


# ─── 5. 度量台负控：校验器必须能抓到「停在进不去的点上」────────────

def test_lab_checker_catches_stop_at_blocked_point():
    """配对负控：同一份方案，只把订单标成 BLOCKED → check_plan 必须报违规。"""
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0), _off_s2("V7", 600.0)]
    m = _matrix(stations)
    plain = PlanOrder(orderId="K7", orderType=OrderType.PICKUP, stationId="V7", itemCount=1)
    blocked = PlanOrder(orderId="K7", orderType=OrderType.PICKUP, stationId="V7",
                        itemCount=1, roadAccess="BLOCKED")
    out = solve(_request(stations, [plain]), m)
    assert out.status == "feasible" and _door_visit(out, "K7", "V7"), \
        "对照组本身没上门（用例失去内容）"

    # 没标 BLOCKED 时这份方案合规
    assert not any("ROAD" in e for e in check_plan(_request(stations, [plain]), m, out)), \
        check_plan(_request(stations, [plain]), m, out)
    # 同一份方案，一旦业务侧判定该点进不去，它就是违规路单
    errs = check_plan(_request(stations, [blocked]), m, out)
    assert any("ROAD" in e for e in errs), f"度量台放过了「停在公交进不去的点上」：{errs}"


# ─── 6. 配对货运单（PlanShipment.roadAccess）：生产主力形态 ──────────

def test_blocked_shipment_moves_both_legs_to_station_handover():
    """村→村的配对货运单才是生产主力，它也得能带通行预检结论。

    任一腿进不去就整单改站点交接：先取后送必须同车，不能只挪一条腿
    （只挪揽收腿等于把车开到进不去的送达点）。
    """
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0),
                _st("V8", 6.6, 0.03), _st("V9", 11.1, 0.03)]
    m = _matrix(stations)
    out = solve(_request(stations, [], shipments=[PlanShipment(
        shipmentId="S8", pickupStationId="V8", deliveryStationId="V9",
        quantity=1, roadAccess="BLOCKED")]), m)
    assert out.reason_code != "INTERNAL_ERROR", f"solver 异常：{out.warnings}"
    assert out.status == "feasible", f"两腿都能 1km 内站点交接，不该整批无解：{out.reason_code}"
    assert "S8" in _served(out), f"可交接的配对单被舍弃：{out.unassigned_order_ids}"
    assert not _door_visit(out, "S8", "V8") and not _door_visit(out, "S8", "V9"), \
        "配对单只挪了一条腿（另一腿仍排在公交进不去的点上）"
    assert _at_skeleton(out, "S8"), "站点交接必须落在骨架站上"
    for _, s in _stops_for(out, "S8"):
        assert s.serviceMode == "NEAREST_STATION", s
        assert s.reasonCode == "ROAD_NOT_BUS_ACCESSIBLE", s
    assert check_plan(_request(stations, [], shipments=[PlanShipment(
        shipmentId="S8", pickupStationId="V8", deliveryStationId="V9",
        quantity=1, roadAccess="BLOCKED")]), m, out) == []


def test_unchecked_shipment_is_still_served_door_to_door():
    """正向对照（同一几何，只去掉 roadAccess 这一个字段）：配对单照旧上门揽收、上门送达。

    没有这一条，上面那例可以靠「所有配对单一律站点交接」骗绿。
    """
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0),
                _st("V8", 6.6, 0.03), _st("V9", 11.1, 0.03)]
    m = _matrix(stations)
    req = _request(stations, [], shipments=[PlanShipment(
        shipmentId="S9", pickupStationId="V8", deliveryStationId="V9", quantity=1)])
    out = solve(req, m)
    assert out.status == "feasible", out.reason_code
    assert "S9" in _served(out), "未做预检的配对单被误当成不可通行"
    assert _door_visit(out, "S9", "V8") and _door_visit(out, "S9", "V9"), \
        f"未标注的配对单被强制改成站点交接：{[(s.action, s.stationId) for _, s in _stops_for(out, 'S9')]}"
    assert check_plan(req, m, out) == []


def test_lab_checker_catches_shipment_stopped_at_blocked_point():
    """配对负控：同一份方案，只把配对单标成 BLOCKED → check_plan 必须报违规。

    早先这条规则只查 request.orders，配对货运单（生产主力）完全不在判据里。
    """
    stations = [_st("S1", 0.0), _st("S2", 6.0), _st("S3", 12.0),
                _st("V8", 6.6, 0.03), _st("V9", 11.1, 0.03)]
    m = _matrix(stations)
    plain = PlanShipment(shipmentId="SA", pickupStationId="V8", deliveryStationId="V9", quantity=1)
    blocked = PlanShipment(shipmentId="SA", pickupStationId="V8", deliveryStationId="V9",
                           quantity=1, roadAccess="BLOCKED")
    out = solve(_request(stations, [], shipments=[plain]), m)
    assert out.status == "feasible" and _door_visit(out, "SA", "V8"), "对照组本身没上门（用例失去内容）"
    assert not any("ROAD" in e for e in check_plan(_request(stations, [], shipments=[plain]), m, out))
    errs = check_plan(_request(stations, [], shipments=[blocked]), m, out)
    assert any("ROAD_ACCESS_VISITED" in e for e in errs), \
        f"度量台对配对单的不可通行点位视而不见：{errs}"
