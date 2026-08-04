package cn.iocoder.yudao.module.transport.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

public interface ErrorCodeConstants {
    ErrorCode STATION_NOT_EXISTS = new ErrorCode(1_005_001_000, "Station not found");
    ErrorCode VEHICLE_NOT_EXISTS = new ErrorCode(1_005_002_000, "Vehicle not found");
    ErrorCode ROUTE_NOT_EXISTS = new ErrorCode(1_005_003_000, "Route not found");
    ErrorCode DRIVER_NOT_EXISTS = new ErrorCode(1_005_004_000, "Driver not found");
}
