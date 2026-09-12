package cn.iocoder.yudao.module.transport.service.monitoring;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一车辆位置快照（Read Model）——车辆位置的唯一读模型。
 *
 * 数据来源优先级：REAL / REAL_STALE > SIMULATED（模拟引擎或确定性班次模拟）> OFFLINE
 * - REAL：司机端 GPS 上报（transport_vehicle_location，15 分钟内有效；5 分钟内为 REAL_FRESH）
 * - SIMULATED：SimulationEngine 模拟运行（开发模式）或 DeterministicScheduleSimulator（班次时刻表插值）
 * - OFFLINE：既无真实上报、也不在任何班次窗口内（真正没有位置）
 *
 * 快照必须带完整业务上下文（班次/线路/当前站/下一站/进度/ETA），
 * 否则下游（附近公交、监控、司机端）无法把位置关联到线路，只能靠过滤兜底。
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
    /** 班次编号 */
    private Long shiftId;
    /** 线路编号 */
    private Long routeId;
    /** 线路编码 */
    private String routeCode;
    /** 线路名 */
    private String routeName;
    /** 进度百分比 */
    private Integer progress;
    /** 到下一站剩余分钟（模拟/班次插值给出；REAL 由调用方按路网计算） */
    private Double etaToNextStationMinutes;
}
