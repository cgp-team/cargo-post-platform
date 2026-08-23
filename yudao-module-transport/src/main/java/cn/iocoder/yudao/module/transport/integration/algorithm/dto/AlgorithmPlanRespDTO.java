package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 规划结果，对齐契约 PlanResult。
 * 预计耗时、收入、成本、评分等指标由业务后端自行估算，不在本 DTO 内。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmPlanRespDTO {

    /** 完整解 */
    public static final String STATUS_FEASIBLE = "feasible";
    /** 无解，附 reasonCode */
    public static final String STATUS_INFEASIBLE = "infeasible";

    public static final String REASON_OVER_CAPACITY = "OVER_CAPACITY";
    public static final String REASON_TIMING_CONFLICT = "TIMING_CONFLICT";
    /** 部分订单可完成；语义待算法组澄清，当前按"只给状态不给方案"处理 */
    public static final String REASON_PARTIAL_ONLY = "PARTIAL_ONLY";

    private String requestId;

    /** feasible / infeasible */
    private String status;

    /** 无解原因码：OVER_CAPACITY / TIMING_CONFLICT / PARTIAL_ONLY */
    private String reasonCode;

    /** 是否为幂等缓存命中 */
    private Boolean cached;

    /** 非致命告警，如 algorithmConfig 超出建议范围 */
    private List<String> warnings;

    /** 算法版本，随镜像管理 */
    private String algorithmVersion;

    /** 默认参数版本 */
    private String parameterVersion;

    /** 全部车辆行驶里程累加值 */
    private Double totalDistance;

    /** 里程单位：degree=经纬度欧氏距离（业务后端需 Haversine 换算），km=路网真实公里（直接使用）。
     *  缺省（null）按 degree 处理（兼容旧版本算法服务） */
    private String distanceUnit;

    /** 里程单位：经纬度欧氏距离（度） */
    public static final String DISTANCE_UNIT_DEGREE = "degree";
    /** 里程单位：路网真实公里 */
    public static final String DISTANCE_UNIT_KM = "km";

    private List<AlgorithmVehiclePlanDTO> vehiclePlans;

    private OffsetDateTime computedAt;

}
