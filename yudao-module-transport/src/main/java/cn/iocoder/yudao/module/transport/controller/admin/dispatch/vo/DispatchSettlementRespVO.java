package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "管理后台 - 返程结算 Response VO（对应故事「返程结算/运营报表」）")
@Data
public class DispatchSettlementRespVO {

    @Schema(description = "总行驶里程（已完成方案 totalDistance 之和，km）")
    private BigDecimal totalDistance;

    @Schema(description = "服务乘客数")
    private Integer passengerCount;

    @Schema(description = "包裹收发量（派送+揽收件数）")
    private Integer parcelCount;

    @Schema(description = "乘客平均等待分钟数（下单→方案创建近似）")
    private Double avgPassengerWaitMinutes;

    @Schema(description = "分车汇总")
    private List<VehicleSettlement> perVehicle;

    @Schema(description = "分车结算")
    @Data
    public static class VehicleSettlement {
        private Long vehicleId;
        @Schema(description = "车牌号")
        private String plateNo;
        @Schema(description = "承运方案数")
        private Integer runCount;
        @Schema(description = "载客总数")
        private Integer passengerCount;
        @Schema(description = "包裹收发量")
        private Integer parcelCount;
    }

}
