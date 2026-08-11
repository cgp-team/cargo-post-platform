package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "用户 APP - 调度任务 Response VO（算法派单结果，预留）")
@Data
public class AppDriverTaskRespVO {

    @Schema(description = "调度明细编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "经停站点编号")
    private Long stationId;
    @Schema(description = "经停站点名称")
    private String stationName;
    @Schema(description = "动作类型(0出发 1接客 2送客 3派送 4揽收 5返回)")
    private Integer actionType;
    @Schema(description = "动作类型名")
    private String actionName;
    @Schema(description = "关联订单编号")
    private Long orderId;
    @Schema(description = "业务订单号")
    private String orderNo;
    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrivalTime;
}
