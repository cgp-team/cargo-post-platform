package cn.iocoder.yudao.module.transport.service.monitoring;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一车辆位置快照（Read Model）。
 *
 * 数据来源优先级：REAL > SIMULATED > OFFLINE
 * - REAL：司机端 GPS 上报（transport_vehicle_location，15分钟内有效）
 * - SIMULATED：SimulationEngine 模拟位置
 * - OFFLINE：无有效数据
 */
@Data
@Builder
public class VehicleLocationSnapshot {

    /** 车辆ID */
    private Long vehicleId;
    /** 经度 */
    private Double longitude;
    /** 纬度 */
    private Double latitude;
    /** 速度(km/h) */
    private Double speedKmh;
    /** 数据来源：REAL / SIMULATED / OFFLINE */
    private String source;
    /** 当前站ID */
    private Long currentStationId;
    /** 当前站名 */
    private String currentStationName;
    /** 下一站ID */
    private Long nextStationId;
    /** 下一站名 */
    private String nextStationName;
    /** 距下一站距离(km) */
    private Double distanceToNextStation;
    /** 到下一站ETA(分钟) */
    private Double etaMinutes;
    /** 关联的模拟运行ID（仅 SIMULATED） */
    private Long simulationRunId;
    /** 模拟时间（仅 SIMULATED） */
    private Long simulationSeconds;
    /** 最后更新时间 */
    private LocalDateTime updatedAt;
    /** 状态：0空闲 1在途 2停用 */
    private Integer status;
    /** 班次编码 */
    private String shiftCode;
    /** 线路名 */
    private String routeName;
    /** 进度百分比 */
    private Integer progress;
}
