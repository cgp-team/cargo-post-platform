package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 运输段进度 Response VO（多段联运）")
@Data
public class AppDriverLegRespVO {

    @Schema(description = "运输段编号")
    private Long id;

    @Schema(description = "订单编号")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "段序号（1=第一段…）")
    private Integer legSequence;

    @Schema(description = "起始站点编号")
    private Long fromStationId;

    @Schema(description = "起始站点名称")
    private String fromStationName;

    @Schema(description = "目的站点编号")
    private Long toStationId;

    @Schema(description = "目的站点名称")
    private String toStationName;

    @Schema(description = "状态：0待分配 1已分配 2运输中 3已到达 4已交接 5异常")
    private Integer status;

    @Schema(description = "状态名")
    private String statusName;

    @Schema(description = "预计出发时间")
    private LocalDateTime estimatedDeparture;

    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrival;

    @Schema(description = "实际到达时间")
    private LocalDateTime actualArrival;

    @Schema(description = "承运车辆编号")
    private Long vehicleId;

    @Schema(description = "车牌号")
    private String plateNo;

    @Schema(description = "本段里程(km)")
    private BigDecimal distanceKm;

    @Schema(description = "本段预计耗时(分钟)")
    private Integer durationMinutes;

    @Schema(description = "导航来源：AMAP 真实道路 / ESTIMATED 估算（前端需明确标注估算）")
    private String navigationSource;

    @Schema(description = "本段是否需要换乘交接（非最终段 true）")
    private Boolean handoverRequired;

    @Schema(description = "换乘交接编号（需交接时）")
    private Long handoverId;

    @Schema(description = "换乘交接状态名（需交接时）")
    private String handoverStatusName;

}
