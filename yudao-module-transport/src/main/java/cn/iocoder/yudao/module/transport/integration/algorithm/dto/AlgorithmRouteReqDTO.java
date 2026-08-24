package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 坐标→坐标单路线查询请求（实时公交 ETA：车辆位置 → 下一站），对齐契约 RouteRequest。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmRouteReqDTO {

    /** 起点（车辆位置），GCJ-02 */
    private RoutePoint origin;

    /** 终点（下一站），GCJ-02 */
    private RoutePoint destination;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutePoint {
        /** GCJ-02 纬度 */
        private Double latitude;
        /** GCJ-02 经度 */
        private Double longitude;
    }

}
