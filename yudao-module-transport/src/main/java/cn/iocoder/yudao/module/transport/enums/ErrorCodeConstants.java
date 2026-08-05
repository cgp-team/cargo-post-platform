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
}
