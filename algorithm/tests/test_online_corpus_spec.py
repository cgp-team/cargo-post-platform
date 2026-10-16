"""线上留痕快照 → 度量台题源（任务 #33）的接入口测试。

夹具是真数据：`tests/fixtures/online_snapshots_sample.jsonl` 由
`benchmarks/lab/export_snapshots.py` 从本机 `transport_algorithm_request` 导出的 49 份
现网快照里取 3 份（`3676764f022d`/`103d2aeb66a2`/`47db044eb8ca`，2026-09-11~13，
状态都是"校验通过"）—— 不是手写的假请求，字段缺什么、id 长什么样都由现网决定。

真实路网图（341MB，未入库）在 CI 上不存在，所以这些用例都注入一个假 RoadNet，
只验"读入口"的三件要紧事：
1. 标签只能是 verify（线上一道题既没有见证解也没有拒绝证明）；
2. OD 缓存键用坐标、矩阵键用请求里的 stationId —— 两边搞混会得到一张"全 0 的矩阵"，
   那会让所有方案看起来又短又可行；
3. 解析失败必须计数并可指名，不能静默丢样本（丢掉的那部分正是最奇怪的题）。
"""
from __future__ import annotations

import json
import os

import pytest

from benchmarks.lab import online_corpus as oc

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "online_snapshots_sample.jsonl")


class _FakeNet:
    """duck-typed RoadNet：不同站点恒 2.0km/250s；若调用方传进来的"坐标键"相同则 0。

    故意用固定值，使矩阵断言与图无关；`estimate` 模式返回"直线×1.3"的同一形状，
    用来验证 `od_estimated_pairs` 真的能识别出估算腿（而不是永远报 0）。
    """

    def __init__(self, estimate: bool = False):
        self.estimate = estimate
        self.calls: list[tuple[str, str]] = []

    def od(self, a, b):
        self.calls.append((a.station_id, b.station_id))
        if a.station_id == b.station_id:
            return (0.0, 0.0)
        if not self.estimate:
            return (2.0, 250.0)
        from app.routing.local_routing import haversine_m
        straight = haversine_m(a.wgs, b.wgs) / 1000.0
        return (round(straight * 1.3, 4), round(straight * 1.3 / 8.0 * 1000, 1))


def _records():
    return oc.iter_raw(FIXTURE)


# ─── 1. 读入口：真快照 → verify 题 ─────────────────────────────

def test_fixture_holds_three_real_snapshots_and_parses_all():
    recs = _records()
    assert len(recs) == 3
    cov = oc.Coverage()
    insts = [oc.to_instance(r, _FakeNet(), cov) for r in recs]
    assert all(i is not None for i in insts)
    assert cov.snapshots == 3 and cov.parsed_failed == 0


def test_online_instances_are_labelled_verify_only():
    """没有见证解也没有拒绝证明 ⇒ 只能问"方案合不合规"。

    如果有人把线上题标成 serve/reject，可行率分母就被没有真值的样本污染了 ——
    这条断言就是那道闸。
    """
    for rec in _records():
        inst = oc.to_instance(rec, _FakeNet())
        assert inst is not None
        assert inst.expect == "verify", "线上快照不得被标成 serve/reject"
        assert inst.witness_feasible is False
        assert inst.meta["source"] == "online"
        assert inst.instance_id.startswith("online-")


def test_instance_ids_are_keyed_by_snapshot_hash_not_request_id():
    ids = {oc.to_instance(r, _FakeNet()).instance_id for r in _records()}
    assert len(ids) == 3, "三份不同快照必须得到三道不同的题"
    for iid in ids:
        assert len(iid) == len("online-") + 12


# ─── 2. 矩阵：缓存按坐标、键按 stationId ───────────────────────

def test_matrix_keys_are_exactly_the_request_station_ids_including_depot():
    rec = _records()[2]  # 16 站点那份
    inst = oc.to_instance(rec, _FakeNet())
    request_ids = {rec["request"]["depot"]["stationId"]} | {
        s["stationId"] for s in rec["request"]["stations"]}
    assert set(a for a, _ in inst.matrix) == request_ids, (
        "矩阵键必须与请求里的 stationId 同一套：不一致时算法查不到腿长会静默按 0 处理")
    n = len(request_ids)
    assert len(inst.matrix) == n * n


def test_matrix_diagonal_is_zero_and_legs_are_the_net_values():
    inst = oc.to_instance(_records()[0], _FakeNet())
    depot = inst.request.depot.stationId
    assert inst.matrix[(depot, depot)] == (0.0, 0.0)
    other = next(s.stationId for s in inst.request.stations if s.stationId != depot)
    assert inst.matrix[(depot, other)] == (2.0, 250.0)


def test_od_cache_is_keyed_by_coordinates_not_database_ids():
    """留痕里的 stationId 是业务库自增号（"107"），公交站点 ID 也可能是纯数字。

    共用一张 OD 缓存就会把两个不同地点算成同一条腿。这里要求送进 RoadNet 的
    `RealStop.station_id`（即缓存键）必须是坐标串，而不是请求里的 ID。
    """
    net = _FakeNet()
    rec = _records()[0]
    oc.to_instance(rec, net)
    assert net.calls, "没有一次 OD 调用 ⇒ 这条通路根本没走"
    request_ids = {rec["request"]["depot"]["stationId"]} | {
        s["stationId"] for s in rec["request"]["stations"]}
    for a, b in net.calls:
        assert "," in a and "," in b, f"OD 缓存键看起来是站点 ID 而不是坐标：{a}|{b}"
        assert a not in request_ids and b not in request_ids


def test_estimated_legs_are_counted_instead_of_passing_as_real_roads():
    """图上不可达时 roadnet 会按直线×1.3 估算；读数必须把这部分单独计出来。

    #35 记的是"线上其实跑在直线口径"，如果这里也静默混进估算腿，
    离线与生产的差距就会被这份"真实语料"抹平 —— 那正是要防的自欺。
    """
    honest = oc.to_instance(_records()[2], _FakeNet())
    estimated = oc.to_instance(_records()[2], _FakeNet(estimate=True))
    assert honest.meta["od_estimated_pairs"] == 0
    assert estimated.meta["od_estimated_pairs"] > 0, "估算腿检测失效：直线口径混进了真实读数"
    assert estimated.meta["od_pairs"] == honest.meta["od_pairs"]


# ─── 3. 覆盖计数与坏样本 ───────────────────────────────────────

def test_coverage_counts_current_production_gap_honestly():
    """当前留痕里订单侧既没有时效也没有重量体积（快照出自 §15 贯通之前）。

    这条断言写的是"读数"，不是"愿望"：等现网跑上带时效的构建后它会红，
    那时应该改的是注释与台账（说明覆盖已建立），而不是把断言删掉。
    """
    cov = oc.coverage(FIXTURE)
    assert cov.snapshots == 3
    assert cov.orders == 3 and cov.deliveries + cov.pickups == 3
    assert cov.passengers == 0, "留痕里还没有客运单 ⇒ 别声称线上语料覆盖了载客约束"
    assert cov.order_with_timeliness == 0
    assert cov.order_with_weight == 0
    assert cov.shipments == 20
    # 实测读数：3 份样本里只有 1 份的 3 台车带骨架；全量 49 份是 45/122 台车带骨架
    # ⇒ "每车都有骨架"不是线上现状，读数要区分这两类题
    assert cov.vehicles_with_skeleton == 3


def test_coverage_counter_is_not_vacuous_paired_control():
    """反向证明上一条的 0 是"数据没有"，不是"计数器不认"。

    把同一份真快照的订单塞上时效/重量/roadAccess 再数一次 ⇒ 三个计数必须都涨。
    没有这条，`== 0` 那组断言在计数器坏掉时也会绿。
    """
    rec = json.loads(json.dumps(_records()[0]))
    order = rec["request"]["orders"][0]
    order.update({"readyTime": "2026-09-11T20:10:00+08:00",
                  "deliveryDeadline": "2026-09-11T20:40:00+08:00",
                  "weightKg": 12.5, "roadAccess": "BLOCKED"})
    cov = oc.Coverage()
    assert oc.to_instance(rec, _FakeNet(), cov) is not None
    assert cov.order_with_timeliness == 1
    assert cov.order_with_weight == 1
    assert cov.order_with_road_access == 1


def test_unparsable_snapshot_is_counted_and_named():
    cov = oc.Coverage()
    bad = {"snapshot_hash": "deadbeef0000", "created_at": "x", "db_status": 1,
           "request": {"batchStart": "not-a-datetime"}}
    assert oc.to_instance(bad, _FakeNet(), cov) is None
    assert cov.parsed_failed == 1
    assert cov.notes and "deadbeef0000" in cov.notes[0], "丢样本要能指名是哪一份"


def test_missing_export_file_points_at_the_exporter(tmp_path):
    with pytest.raises(FileNotFoundError) as exc:
        oc.iter_raw(str(tmp_path / "nope.jsonl"))
    assert "export_snapshots" in str(exc.value), "报错要指下一步动作，不然下个人只能猜"
