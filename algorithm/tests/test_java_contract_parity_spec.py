"""Java 调用方与算法服务之间的字段/超参对齐锚点。

为什么要有这一份：算法侧这半年补上的能力（逐单时效、公交通行预检、载重体积、超参默认值）
全部要求调用方真的把字段送进来。Java 的 DTO 少一个字段，Python 侧的实现就退化成
"读一个永远为 None 的可选字段"，测试照样绿、指标照样好看，生产里却什么都没发生。
这类缺陷静态读代码容易漏（两边各自都"看着对"），所以把对齐关系钉成用例：
锚点是**对方源码里的字段名与字面值**，不是我们自己的一份复刻。

口径：只解析源码文本，不依赖 Java 编译产物，因此本机与 CI（整仓 checkout）都能跑。
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.models import AlgorithmConfig

REPO_ROOT = Path(__file__).resolve().parents[2]
JAVA_DTO_DIR = REPO_ROOT / (
    "yudao-module-transport/src/main/java/cn/iocoder/yudao/module/transport"
    "/integration/algorithm/dto"
)
PLANNER_JAVA = REPO_ROOT / (
    "yudao-module-transport/src/main/java/cn/iocoder/yudao/module/transport"
    "/service/dispatch/AutoDispatchPlanner.java"
)

pytestmark = pytest.mark.skipif(
    not JAVA_DTO_DIR.is_dir(),
    reason="本工作副本没有 Java 侧源码（只跑算法子树时跳过）",
)


def _java_text(rel_path: Path) -> str:
    text = rel_path.read_text(encoding="utf-8")
    # 自检：读到的必须是真正的 DTO 源文件，否则下面的断言会"全是 None 所以全过"
    assert "package cn.iocoder.yudao" in text, rel_path
    return text


def _declared_fields(java_text: str) -> set[str]:
    """抓 Java DTO 里声明的实例字段名（`private X name;`，忽略常量与方法）。"""
    return set(re.findall(r"^\s{4}private\s+[\w.<>\[\]]+\s+(\w+)\s*;",
                          java_text, flags=re.MULTILINE))


# ─── 1. 请求侧字段：Python 读的字段必须真的有人送 ──────────────────

@pytest.mark.parametrize("java_file,required", [
    ("AlgorithmShipmentDTO.java", {
        "shipmentId", "pickupStationId", "deliveryStationId", "quantity",
        "weightKg", "volumeM3",
        # 这四个字段在 app/haco 里已真正参与判定；调用方不送 = 生产批次永远没有逐单时效
        "readyTime", "pickupDeadline", "deliveryDeadline", "priority",
        "roadAccess",
    }),
    ("AlgorithmOrderDTO.java", {"orderId", "orderType", "itemCount", "weightKg",
                                "volumeM3", "roadAccess",
                                # 任务 #32：站点货运单（村↔场站，生产主流量）的逐单时效。
                                # 只有配对单带时效 = 主流量没有任何承诺时刻，迟到方案照样报可行。
                                # pickupDeadline/priority 故意不列：业务侧尚无对应字段，
                                # 要求"必须声明"会诱导有人填一个编造的时刻。
                                "readyTime", "deliveryDeadline"}),
    ("AlgorithmVehicleDTO.java", {"vehicleId", "passengerCapacity", "cargoCapacity",
                                  "initialPassengerLoad", "initialCargoLoad", "skeleton",
                                  # 没有它，PlanShipment.weightKg 的载重校验在生产里走不到
                                  "cargoWeightCapacityKg"}),
    ("AlgorithmPlanReqDTO.java", {"batchStart", "batchEnd", "depot", "stations",
                                  "vehicles", "orders", "shipments"}),
])
def test_java_request_dto_declares_every_field_the_solver_reads(java_file, required):
    declared = _declared_fields(_java_text(JAVA_DTO_DIR / java_file))
    assert declared, f"{java_file} 没解析出任何字段（正则失效，不是对齐成功）"
    missing = required - declared
    assert not missing, (
        f"{java_file} 缺字段 {sorted(missing)}：算法侧会按缺省值运行，"
        f"契约里写了的能力在生产侧等于没实现"
    )


def test_unknown_field_is_not_matched_by_the_parser():
    """负控：解析器不能"什么都在"。要求一个不存在的字段名必须判缺失。"""
    declared = _declared_fields(_java_text(JAVA_DTO_DIR / "AlgorithmShipmentDTO.java"))
    assert "shipmentWeightTonnage" not in declared


# ─── 2. 响应侧字段：算法交出的解释信息必须有人接 ────────────────────

def test_java_response_dto_can_receive_partial_solution_and_explanations():
    """unassignedOrderIds 是"这一趟带不下"的唯一出口；accepted/reasonCode/servicePoint
    是"为什么没在这儿服务"的解释。DTO 少字段 → 后端要么整批失败、要么静默丢解释。"""
    resp = _declared_fields(_java_text(JAVA_DTO_DIR / "AlgorithmPlanRespDTO.java"))
    assert {"status", "reasonCode", "vehiclePlans", "unassignedOrderIds",
            "totalDistance", "distanceUnit", "warnings", "cached"} <= resp, \
        f"响应 DTO 缺 {sorted({'status', 'reasonCode', 'vehiclePlans', 'unassignedOrderIds', 'totalDistance', 'distanceUnit', 'warnings', 'cached'} - resp)}"
    stop = _declared_fields(_java_text(JAVA_DTO_DIR / "AlgorithmRouteStopDTO.java"))
    assert {"stationId", "orderId", "action", "accepted", "serviceMode",
            "servicePoint", "detourDistance", "reasonCode"} <= stop


# ─── 3. 超参默认值：两边必须是同一套口径 ────────────────────────────

def test_java_default_hyperparameters_match_solver_defaults():
    """生产下发 ant_count/max_iterations 若与算法默认值不一致，
    跑的就是另一套口径：离线 A/B 的结论不能外推到线上。"""
    text = _java_text(PLANNER_JAVA)
    sent = dict(re.findall(r'config\.put\("(\w+)",\s*([\w.]+)\)', text))
    assert sent, "没从 AutoDispatchPlanner.java 解析出任何 config.put（正则失效）"
    cfg = AlgorithmConfig()
    assert int(sent["ant_count"]) == cfg.ant_count, (
        f"Java 下发 ant_count={sent['ant_count']}，算法默认 {cfg.ant_count}"
    )
    assert int(sent["max_iterations"]) == cfg.max_iterations, (
        f"Java 下发 max_iterations={sent['max_iterations']}，算法默认 {cfg.max_iterations}"
    )
