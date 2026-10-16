"""任务 #32：站点货运单（村↔场站）的逐单时效要真的下发、并且真的被约束。

缺陷事实（写这份用例时已核实，不是推测）：
- Java `AlgorithmOrderDTO` 原本没有 readyTime/deliveryDeadline 两列，
  `DispatchServiceImpl.toAlgorithmOrders` 只在配对单（PlanShipment）分支填时效；
- Python `PlanOrder` 原本也没有这四列，`v14_solver._encode_tasks` 的 DELIVERY/PICKUP
  分支不填 TaskBlock 的时效字段。
⇒ 生产主流量（一张单只有一端是村点）没有任何承诺时刻进算法，迟到的派送方案照样报 feasible。

锚点决定（不是"能填就填"）：
- DELIVERY（场站→村）有 DELIVER 事件 ⇒ deliveryDeadline 有锚点，接入即生效；
- PICKUP（村→场站）只有 PICKUP 事件 ⇒ readyTime 有锚点（没到能取件的时刻车要等），
  deliveryDeadline **故意不接**：单节点任务在早筛里 leg 恒为 0
  （`delivery_station == pickup_station`），接上等于拿"村点揽完的时刻 + 一次派送服务时长"
  去比"送到场站截止"，既没算回程也没资格加服务时长 ⇒ 会凭空多舍弃。
  这一侧目前由批窗 batchEnd 兜住，要真判得给收班事件加锚点（配套实际到时刻回流）。

用例分两层：编码层（契约 → TaskBlock 的映射，含那处故意不对称）+ 判据层
（FeasibilityEngine 真的按订单时效判迟到/真的会等）。后者防的是"字段接通了但没人查"
—— 那等于没修，只是把缺陷从"没下发"挪到"下发了但忽略"。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.haco.encoding import TaskType
from app.haco.feasibility_engine import FeasibilityEngine
from app.haco.route_genome import RouteGenome
from app.haco.v14_solver import _encode_tasks
from app.models import (
    AlgorithmConfig,
    AlgorithmMode,
    OrderType,
    PlanOrder,
    PlanRequest,
    Station,
    StopAction,
    Vehicle,
)
from app.validators import service_duration

BASE = datetime(2026, 10, 12, 0, 0, tzinfo=timezone.utc)  # epoch 3600+ 由 base_time_s 决定
BASE_TIME_S = 3600.0

STATIONS = {
    sid: Station(stationId=sid, longitude=0.0 + i, latitude=0.0)
    for i, sid in enumerate(["D", "S1", "S2", "S3"])
}
# 任意两站 1km / 60s：判据结果只依赖这张表，不依赖几何估算
MATRIX = {
    (a, b): (0.0, 0.0) if a == b else (1.0, 60.0)
    for a in STATIONS for b in STATIONS
}


def _at(offset_s: float) -> datetime:
    return datetime.fromtimestamp(BASE_TIME_S + offset_s, tz=timezone.utc)


def _request(orders):
    return PlanRequest(
        requestId="t32", batchStart=BASE, batchEnd=_at(3000.0),
        depot=STATIONS["D"], stations=list(STATIONS.values()),
        vehicles=[Vehicle(vehicleId=1, passengerCapacity=5, cargoCapacity=5)],
        orders=list(orders), shipments=[],
        algorithmConfig=AlgorithmConfig(algorithmMode=AlgorithmMode.HACO, randomSeed=7),
    )


def _encoded(order: PlanOrder):
    return _encode_tasks(_request([order]))[0]


def _single_node_route(task):
    """把单节点任务插进骨架间隙：只有一个它自己的事件 + 收班回场站。"""
    g = RouteGenome(vehicle_index=0, vehicle_id=1, depot_station="D",
                    skeleton=["D", "S1", "S2", "S3"])
    g.insert_task(task, 1, None)
    return g


def _check(task):
    return FeasibilityEngine().check(
        _single_node_route(task), {task.task_id: task},
        passenger_capacity=5, cargo_capacity=5,
        initial_passenger_load=0, initial_cargo_load=0,
        station_map=STATIONS, matrix=MATRIX,
        max_duration=3000.0, base_time_s=BASE_TIME_S,
    )


# ─── 1. 编码层：契约字段 → TaskBlock ────────────────────────────

def test_delivery_order_carries_its_timeliness_into_the_task_block():
    task = _encoded(PlanOrder(
        orderId="D1", orderType=OrderType.DELIVERY, stationId="S2", itemCount=1,
        readyTime=_at(300.0), deliveryDeadline=_at(900.0), priority=3,
    ))
    assert task.task_type == TaskType.DELIVERY
    assert task.ready_time_s == _at(300.0).timestamp()
    assert task.delivery_deadline_s == _at(900.0).timestamp()
    assert task.priority == 3, "优先级决定稀缺舱位归属（_canonical_order），丢了就等于没传"


def test_pickup_order_encodes_ready_time_but_deliberately_not_its_delivery_deadline():
    task = _encoded(PlanOrder(
        orderId="K1", orderType=OrderType.PICKUP, stationId="S2", itemCount=1,
        readyTime=_at(300.0), deliveryDeadline=_at(900.0), priority=2,
    ))
    assert task.task_type == TaskType.PICKUP
    assert task.ready_time_s == _at(300.0).timestamp(), "揽收要等到能取件，这条有 PICKUP 事件锚点"
    assert task.priority == 2
    # 这条断言是"故意的"，不是遗漏：见模块 docstring。若哪天给收班事件加了锚点，
    # 要连同 _encode_tasks 里的注释一起改，而不是让它悄悄变成"填了但判错"。
    assert task.delivery_deadline_s is None, (
        "揽收单没有 DELIVER 事件，deliveryDeadline 不得被塞进 TaskBlock —— "
        "早筛会拿揽收完成时刻去比送达截止，等于凭空多舍弃"
    )


def test_passenger_order_ignores_timeliness_even_if_a_client_sends_it():
    # 业务底线：公交每站必停拉客、无预约乘车。给算法一个"乘客可上车时刻"
    # 等于授权它为了带货在站外等客 —— 这条不能因为字段贯通而顺带破掉。
    task = _encoded(PlanOrder(
        orderId="P1", orderType=OrderType.PASSENGER,
        boardingStationId="S1", alightingStationId="S2",
        readyTime=_at(300.0), deliveryDeadline=_at(900.0),
    ))
    assert task.task_type == TaskType.PASSENGER
    assert task.ready_time_s is None and task.delivery_deadline_s is None


def test_legacy_snapshot_without_the_new_columns_still_decodes():
    """留痕表里躺着 §15 贯通**之前**的请求原文（实测 49 份，orders 只有
    orderId/orderType/stationId/itemCount 四列）。
    新字段必须可缺省 —— 否则历史快照重放、以及现网灰度期间"新算法 + 旧后端"
    都会直接 422，那不是我改一个契约字段该有的后果。
    """
    legacy = {
        "requestId": "legacy-1", "batchStart": "2026-09-11T19:30:00+08:00",
        "batchEnd": "2026-09-11T20:00:00+08:00",
        "depot": {"stationId": "107", "longitude": 106.579, "latitude": 29.529},
        "stations": [{"stationId": "101", "longitude": 106.55, "latitude": 29.51}],
        "vehicles": [{"vehicleId": 12, "passengerCapacity": 8, "cargoCapacity": 4}],
        "orders": [{"orderId": "341", "orderType": "DELIVERY", "stationId": "101", "itemCount": 1}],
    }
    req = PlanRequest.model_validate(legacy)
    task = _encode_tasks(req)[0]
    assert task.ready_time_s is None and task.delivery_deadline_s is None
    assert task.priority == 0


def test_missing_timeliness_stays_none_not_epoch_zero():
    # 业务侧没承诺 ≠ 承诺在 1970-01-01：填 0 会让每张无承诺的单被立刻判迟到
    task = _encoded(PlanOrder(
        orderId="D2", orderType=OrderType.DELIVERY, stationId="S2", itemCount=1,
    ))
    assert task.ready_time_s is None
    assert task.delivery_deadline_s is None
    assert task.priority == 0


# ─── 2. 判据层：订单时效真的参与可行性判定 ──────────────────────

def test_late_delivery_order_is_infeasible_and_on_time_one_is_not():
    """同一条路线、同一个派送点，只把送达截止挪 1 秒 ⇒ 结论必须翻转。

    数值可手算：D→S2 一段 60s，DELIVER 服务 service_duration，完成时刻 =
    3600 + 60 + 服务时长。截止压在完成时刻之前 ⇒ ETA_MISSED_DELIVERY。
    """
    finish = BASE_TIME_S + MATRIX[("D", "S2")][1] + service_duration(StopAction.DELIVER)

    late = _check(_encoded(PlanOrder(
        orderId="D-LATE", orderType=OrderType.DELIVERY, stationId="S2", itemCount=1,
        deliveryDeadline=_at(finish - BASE_TIME_S - 1.0),
    )))
    assert not late.feasible, "迟到方案被报成可行 ⇒ 时效字段接了但没人判"
    assert late.reason_code == "ETA_MISSED_DELIVERY", late.reason_code

    on_time = _check(_encoded(PlanOrder(
        orderId="D-OK", orderType=OrderType.DELIVERY, stationId="S2", itemCount=1,
        deliveryDeadline=_at(finish - BASE_TIME_S + 1.0),
    )))
    assert on_time.feasible, on_time.reason_code


def test_pickup_order_waits_for_ready_time_and_that_wait_can_break_its_own_deadline():
    """readyTime 的"上岗证明"：车到得早必须等到能取件，等待把完成时刻推后。

    D→S2 到达 3660，ready 3800 ⇒ 等到 3800 再揽收，完成 = 3800 + PICKUP 服务。
    揽收截止压在完成时刻之前只有"真的等了"才会违反；如果实现忽略 readyTime，
    完成时刻是 3720，这一条会判可行 —— 那正是我们要红的形状。
    """
    ready_offset = 200.0
    finish = BASE_TIME_S + ready_offset + service_duration(StopAction.PICKUP)

    blocked = _check(_encoded(PlanOrder(
        orderId="K-TIGHT", orderType=OrderType.PICKUP, stationId="S2", itemCount=1,
        readyTime=_at(ready_offset),
        pickupDeadline=_at(finish - BASE_TIME_S - 1.0),
    )))
    assert not blocked.feasible, "等待没生效：车被允许在件没好之前取货，或截止判据没接线"
    assert blocked.reason_code == "ETA_MISSED_PICKUP", blocked.reason_code

    room = _check(_encoded(PlanOrder(
        orderId="K-OK", orderType=OrderType.PICKUP, stationId="S2", itemCount=1,
        readyTime=_at(ready_offset),
        pickupDeadline=_at(finish - BASE_TIME_S + 1.0),
    )))
    assert room.feasible, room.reason_code
