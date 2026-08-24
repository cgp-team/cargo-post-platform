package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 坐标→坐标单路线结果，对齐契约 RouteResponse。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmRouteRespDTO {

    /** 是否可用（false=路线不可达） */
    private Boolean available;

    /** 距离（恒为公里 km） */
    private Double distanceKm;

    /** 行驶秒数（高德真实秒；euclidean 按均速换算的秒） */
    private Double durationSeconds;

    /** 数据来源：amap=高德路网 / euclidean=直线估算 */
    private String provider;

    /** 不可用时原因码（如 ROUTE_UNAVAILABLE） */
    private String reasonCode;

}
