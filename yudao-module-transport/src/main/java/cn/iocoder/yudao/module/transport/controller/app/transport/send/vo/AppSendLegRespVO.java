package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "用户 APP - 多段运输进度 Response VO")
@Data
public class AppSendLegRespVO {

    @Schema(description = "段序号（1=第一段…）")
    private Integer legSequence;

    @Schema(description = "起始站点名称")
    private String fromStationName;

    @Schema(description = "目的站点名称")
    private String toStationName;

    @Schema(description = "状态：0待分配 1已分配 2运输中 3已到达 4已交接 5异常")
    private Integer status;

    @Schema(description = "状态名")
    private String statusName;

    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrival;

    @Schema(description = "实际到达时间")
    private LocalDateTime actualArrival;

    @Schema(description = "承运司机姓名")
    private String driverName;

    @Schema(description = "承运车牌号")
    private String plateNo;

}
