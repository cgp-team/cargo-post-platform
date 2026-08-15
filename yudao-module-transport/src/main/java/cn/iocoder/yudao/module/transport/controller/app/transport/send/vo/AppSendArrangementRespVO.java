package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "用户 APP - 我的乘车安排 Response VO（村民到站通知：客运订单已安排/在途，含车辆与实时公交入口）")
@Data
public class AppSendArrangementRespVO {

    @Schema(description = "订单编号")
    private Long orderId;

    @Schema(description = "业务订单号")
    private String orderNo;

    @Schema(description = "订单状态：2 已分配 3 已发车 4 已完成")
    private Integer status;

    @Schema(description = "订单状态名称")
    private String statusName;

    @Schema(description = "上车站编号")
    private Long boardingStationId;

    @Schema(description = "上车站名称")
    private String boardingStationName;

    @Schema(description = "下车站编号")
    private Long alightingStationId;

    @Schema(description = "下车站名称")
    private String alightingStationName;

    @Schema(description = "调度方案编号")
    private Long planId;

    @Schema(description = "方案状态：0 待审核 1 已下发 2 执行中 3 已完成")
    private Integer planStatus;

    @Schema(description = "承运车辆车牌号（已派车时有值）")
    private String vehiclePlateNo;

}
