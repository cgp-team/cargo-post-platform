package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 两站点距离/耗时查询结果，对齐契约 DistanceResponse。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmDistanceRespDTO {

    /** 里程单位：经纬度欧氏距离（度），业务后端需 Haversine 换算 */
    public static final String DISTANCE_UNIT_DEGREE = "degree";
    /** 里程单位：路网真实公里，直接使用 */
    public static final String DISTANCE_UNIT_KM = "km";

    private String requestId;

    /** degree / km */
    private String distanceUnit;

    /** 查询的站点对结果（本接口固定 1 对） */
    private List<AlgorithmDistancePairDTO> pairs;

    private OffsetDateTime computedAt;

}
