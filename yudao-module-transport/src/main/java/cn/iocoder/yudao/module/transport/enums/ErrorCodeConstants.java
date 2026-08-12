package cn.iocoder.yudao.module.transport.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

public interface ErrorCodeConstants {
    // Station
    ErrorCode STATION_NOT_EXISTS = new ErrorCode(1_005_001_000, "站点不存在");
    ErrorCode STATION_CODE_DUPLICATE = new ErrorCode(1_005_001_001, "站点编码已存在");
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
    // Driver App（司机端）
    ErrorCode DRIVER_NOT_FOUND = new ErrorCode(1_005_011_000, "未找到司机档案");
    ErrorCode DRIVER_VEHICLE_NOT_BOUND = new ErrorCode(1_005_011_001, "司机未绑定车辆");
    ErrorCode DRIVER_ORDER_STATUS_ILLEGAL = new ErrorCode(1_005_011_002, "订单当前状态不允许该操作");
    ErrorCode DRIVER_SHIFT_EXECUTION_NOT_EXISTS = new ErrorCode(1_005_011_003, "班次执行记录不存在，请先发车");
}
