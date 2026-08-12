package cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "用户 APP - 实时公交 Response VO")
@Data
public class AppBusRespVO {

    @Schema(description = "车辆编号")
    private Long busId;

    @Schema(description = "车牌号")
    private String plateNo;

    @Schema(description = "班次编码（如 SH001）")
    private String shiftCode;

    @Schema(description = "线路名称")
    private String routeName;

    @Schema(description = "起点站")
    private String startStation;

    @Schema(description = "终点站")
    private String endStation;

    @Schema(description = "状态：1 在途，0 空闲/已到站")
    private Integer status;

    @Schema(description = "下一站名称")
    private String nextStation;

    @Schema(description = "预计到站分钟数（由进度与班次计划时长估算）")
    private Integer etaMinutes;

    @Schema(description = "线路进度(0-100)")
    private Integer progress;

    @Schema(description = "速度(km/h)")
    private Double speedKmh;

}
