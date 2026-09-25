package cn.iocoder.yudao.module.transport.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

public interface ErrorCodeConstants {
    // Station
    ErrorCode STATION_NOT_EXISTS = new ErrorCode(1_005_001_000, "站点不存在");
    ErrorCode STATION_CODE_DUPLICATE = new ErrorCode(1_005_001_001, "站点编码已存在");
    ErrorCode STATION_DISABLED = new ErrorCode(1_005_001_002, "所选站点已停用，请重新选择");
    // Vehicle
    ErrorCode VEHICLE_NOT_EXISTS = new ErrorCode(1_005_002_000, "车辆不存在");
    ErrorCode VEHICLE_PLATE_DUPLICATE = new ErrorCode(1_005_002_001, "车牌号已存在");
    // Route
    ErrorCode ROUTE_NOT_EXISTS = new ErrorCode(1_005_003_000, "线路不存在");
    ErrorCode ROUTE_CODE_DUPLICATE = new ErrorCode(1_005_003_001, "线路编码已存在");
    // Driver
    ErrorCode DRIVER_NOT_EXISTS = new ErrorCode(1_005_004_000, "司机不存在");
    ErrorCode DRIVER_LICENSE_DUPLICATE = new ErrorCode(1_005_004_001, "驾驶证号已存在");
    // Shift
    ErrorCode SHIFT_NOT_EXISTS = new ErrorCode(1_005_005_000, "班次不存在");
    ErrorCode SHIFT_CODE_DUPLICATE = new ErrorCode(1_005_005_001, "班次编码已存在");
    // Order
    ErrorCode ORDER_NOT_EXISTS = new ErrorCode(1_005_006_000, "订单不存在");
    ErrorCode ORDER_NO_DUPLICATE = new ErrorCode(1_005_006_001, "订单号已存在");
    // 算法适配层
    ErrorCode ALGORITHM_SERVICE_UNAVAILABLE = new ErrorCode(1_005_007_000, "算法服务不可用");
    ErrorCode ALGORITHM_INVALID_INPUT = new ErrorCode(1_005_007_001, "算法请求参数错误：{}");
    ErrorCode ALGORITHM_OVER_LIMIT = new ErrorCode(1_005_007_002, "算法任务超出规模上限（30 站点 / 25 订单 / 3 车）");
    ErrorCode ALGORITHM_RESULT_INVALID = new ErrorCode(1_005_007_003, "算法结果校验失败：{}");
    ErrorCode ALGORITHM_RESULT_NOT_FOUND = new ErrorCode(1_005_007_004, "算法结果不存在或已过期：{}");
    ErrorCode ALGORITHM_TASK_TIMEOUT = new ErrorCode(1_005_007_005, "算法任务轮询超时：{}");
    ErrorCode ALGORITHM_CALL_FAILED = new ErrorCode(1_005_007_006, "算法服务调用失败（HTTP {}）：{}");
    // 调度闭环
    ErrorCode DISPATCH_TASK_NOT_EXISTS = new ErrorCode(1_005_008_000, "调度任务不存在");
    ErrorCode DISPATCH_PLAN_NOT_EXISTS = new ErrorCode(1_005_008_001, "调度方案不存在");
    ErrorCode DISPATCH_PLAN_STATUS_ILLEGAL = new ErrorCode(1_005_008_002, "方案当前状态不允许该操作");
    ErrorCode DISPATCH_POOL_EMPTY = new ErrorCode(1_005_008_003, "当前批次订单池为空");
    ErrorCode DISPATCH_ORDER_NOT_POOLED = new ErrorCode(1_005_008_004, "订单不在订单池中");
    ErrorCode DISPATCH_DEPOT_NOT_EXISTS = new ErrorCode(1_005_008_005, "场站不存在");
    ErrorCode DISPATCH_NO_FEASIBLE = new ErrorCode(1_005_008_006, "算法判定无可行解:{}");
    ErrorCode DISPATCH_SCALE_OVER_LIMIT = new ErrorCode(1_005_008_007, "智能派单规模超出算法上限（30 站点 / 25 订单 / 3 车），当前 {}，请拆分批次或减少订单");
    ErrorCode DISPATCH_ORDER_NOT_COLLECTABLE = new ErrorCode(1_005_008_008, "部分订单当前状态不可归集（仅承运审核通过的待入池订单可入池）");
    ErrorCode DISPATCH_TASK_WINDOW_EMPTY = new ErrorCode(1_005_008_009, "任务窗口 {} 内没有可派订单（{} 单时间窗与本窗口无交集，请调整窗口或等下一班次）");
    ErrorCode DISPATCH_NO_BACKTRACKING = new ErrorCode(1_005_008_010, "车辆已驶过站点、不能掉头取货：{}；请留到下一班次或改派其他线路");
    ErrorCode DISPATCH_VEHICLE_BUSY = new ErrorCode(1_005_008_011, "车辆已被在途方案占用（待审核/已下发/执行中），请更换车辆或稍后再派");
    // Product
    ErrorCode PRODUCT_NOT_EXISTS = new ErrorCode(1_005_009_000, "商品不存在");
    ErrorCode PRODUCT_NAME_DUPLICATE = new ErrorCode(1_005_009_001, "商品名称已存在");
    // Product Order（农产品商城订单）
    ErrorCode PRODUCT_ORDER_NOT_EXISTS = new ErrorCode(1_005_010_000, "订单不存在");
    ErrorCode PRODUCT_ORDER_STATUS_ILLEGAL = new ErrorCode(1_005_010_001, "订单状态不允许该操作");
    ErrorCode PRODUCT_OFF_SHELF = new ErrorCode(1_005_010_002, "商品已下架");
    ErrorCode PRODUCT_STOCK_NOT_ENOUGH = new ErrorCode(1_005_010_003, "商品库存不足");
    ErrorCode PRODUCT_ORDER_NOT_YOURS = new ErrorCode(1_005_010_004, "无权操作该订单");
    ErrorCode PRODUCT_ORDER_USER_NOT_LOGIN = new ErrorCode(1_005_010_005, "请先登录");
    // Send（小程序寄货）
    ErrorCode SEND_ORDER_USER_NOT_LOGIN = new ErrorCode(1_005_010_006, "请先登录");
    ErrorCode SEND_STATIONS_SAME = new ErrorCode(1_005_010_007, "取货站点和送达站点不能相同");
    ErrorCode SEND_ORDER_STATUS_ILLEGAL = new ErrorCode(1_005_010_008, "订单当前状态不允许该操作");
    ErrorCode SEND_ORDER_NOT_YOURS = new ErrorCode(1_005_010_009, "无权操作该订单");
    // Driver App（司机端）
    ErrorCode DRIVER_NOT_FOUND = new ErrorCode(1_005_011_000, "未找到司机档案");
    ErrorCode DRIVER_VEHICLE_NOT_BOUND = new ErrorCode(1_005_011_001, "司机未绑定车辆");
    ErrorCode DRIVER_ORDER_STATUS_ILLEGAL = new ErrorCode(1_005_011_002, "订单当前状态不允许该操作");
    ErrorCode DRIVER_SHIFT_EXECUTION_NOT_EXISTS = new ErrorCode(1_005_011_003, "班次执行记录不存在，请先发车");
    ErrorCode DRIVER_IDENTITY_MISMATCH = new ErrorCode(1_005_011_004, "司机身份与登录账号不符");
    ErrorCode DRIVER_ORDER_NOT_ASSIGNED = new ErrorCode(1_005_011_005, "订单不属于当前司机");
    ErrorCode DRIVER_CARGO_FULL = new ErrorCode(1_005_011_006, "行李舱已满，无法装车");
    ErrorCode DRIVER_STATION_NOT_IN_ROUTE = new ErrorCode(1_005_011_007, "站点不属于该班次线路");
    ErrorCode DRIVER_STATION_ORDER_ILLEGAL = new ErrorCode(1_005_011_008, "到站顺序不合法");
    ErrorCode DRIVER_VEHICLE_NOT_EXISTS = new ErrorCode(1_005_011_009, "人车绑定记录不存在");
    ErrorCode DRIVER_CARGO_PHOTO_REQUIRED = new ErrorCode(1_005_011_010, "请拍摄货物照片后再装车");
    // Postal Pickup（邮快件取件核销）
    ErrorCode POSTAL_PICKUP_CODE_INVALID = new ErrorCode(1_005_013_000, "取件码不正确");
    ErrorCode POSTAL_ALREADY_PICKED = new ErrorCode(1_005_013_001, "该件已取件");
    // Cargo Audit（货运物品审核）
    ErrorCode CARGO_AUDIT_ONLY_CARGO = new ErrorCode(1_005_014_000, "仅货运订单需要审核");
    ErrorCode CARGO_AUDIT_STATUS_ILLEGAL = new ErrorCode(1_005_014_001, "订单当前状态不允许审核");
    ErrorCode CARGO_AUDIT_PENDING = new ErrorCode(1_005_014_002, "订单未通过货运审核，暂不可归集");
    // Notice（平台公告）；段内 1_005_013_000/001 已被邮快件取件占用，从 002 起
    ErrorCode NOTICE_NOT_EXISTS = new ErrorCode(1_005_013_002, "公告不存在");
    // Feedback（意见反馈）；段内 1_005_014_000/001 已被货运审核占用，从 002 起
    ErrorCode FEEDBACK_NOT_EXISTS = new ErrorCode(1_005_014_002, "意见反馈不存在");
    // Multi-Leg（多段联运 / 货物交接 / 订单事件 / 用户通知）
    ErrorCode LEG_NOT_EXISTS = new ErrorCode(1_005_016_000, "运输段不存在");
    ErrorCode LEG_STATUS_ILLEGAL = new ErrorCode(1_005_016_001, "运输段当前状态不允许该操作");
    ErrorCode HANDOVER_NOT_EXISTS = new ErrorCode(1_005_016_002, "交接记录不存在");
    ErrorCode HANDOVER_STATUS_ILLEGAL = new ErrorCode(1_005_016_003, "交接记录当前状态不允许该操作");
    ErrorCode HANDOVER_NOT_ASSIGNED = new ErrorCode(1_005_016_004, "该交接记录不属于当前司机");
    ErrorCode MULTI_LEG_NOT_REQUIRED = new ErrorCode(1_005_016_005, "该订单无需多段联运");
    ErrorCode NOTIFICATION_NOT_EXISTS = new ErrorCode(1_005_016_006, "通知不存在");
    ErrorCode NOTIFICATION_NOT_YOURS = new ErrorCode(1_005_016_007, "无权操作该通知");
    // 站点可达性（用户可达 / 车辆可达 / 可调度，三者独立）
    ErrorCode STATION_NOT_USER_ACCESSIBLE = new ErrorCode(1_005_016_008, "所选站点用户无法到达，请重新选择");
    ErrorCode STATION_NOT_VEHICLE_ACCESSIBLE = new ErrorCode(1_005_016_009, "所选站点车辆无法进入，请更换可服务站点");
    ErrorCode STATION_NOT_DISPATCH_ENABLED = new ErrorCode(1_005_016_010, "所选站点未开放调度，不能作为场站/换乘站");
    // 运输段状态机 / 交接
    ErrorCode LEG_NOT_ASSIGNED = new ErrorCode(1_005_016_011, "该运输段不属于当前司机");
    ErrorCode LEG_TRANSITION_ILLEGAL = new ErrorCode(1_005_016_012, "运输段当前状态不允许该操作：{}");
    ErrorCode HANDOVER_SOURCE_NOT_ARRIVED = new ErrorCode(1_005_016_013, "前序司机尚未确认到达，不能确认接货");
    ErrorCode HANDOVER_TIMEOUT = new ErrorCode(1_005_016_014, "交接超时，已标记异常并转人工处理");
    // 资源时间冲突（车辆/司机不能被两段冲突任务同时占用，需求 §48/§49/§113）
    ErrorCode VEHICLE_TIME_CONFLICT = new ErrorCode(1_005_016_015, "车辆在该时段已有冲突任务：{}");
    ErrorCode DRIVER_TIME_CONFLICT = new ErrorCode(1_005_016_016, "司机在该时段已有冲突任务：{}");
    ErrorCode NO_AVAILABLE_RESOURCE = new ErrorCode(1_005_016_017, "当前无可调度车辆/司机（时段冲突或被占用）");
    // 订单状态机（BE-21：不允许任意跳转，如 EXCEPTION → DELIVERING）
    ErrorCode ORDER_STATUS_TRANSITION_ILLEGAL = new ErrorCode(1_005_016_018, "订单状态不允许该流转：{} → {}");
}
