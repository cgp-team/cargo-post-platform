package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 距离查询的单对结果，对齐契约 DistancePair。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmDistancePairDTO {

    /** 起点（取货站）站点标识 */
    private String fromStationId;

    /** 终点（送达站）站点标识 */
    private String toStationId;

    /** 里程；distanceUnit=km 时为路网公里，degree 时为欧氏度数（业务侧换算） */
    private Double distanceKm;

    /** 行驶秒数（高德路网真实秒；euclidean 直线估算按均速换算的秒；不可达为 null） */
    private Double durationSeconds;

    /** 数据来源：amap=高德路网 / euclidean=直线估算 */
    private String provider;

    /** 是否可用：false=该点对明确不可达（无距离/时长） */
    private Boolean available;

}
