package cn.iocoder.yudao.module.transport.controller.admin.transport.orderevent.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 订单事件 Response VO")
@Data
public class OrderEventRespVO {

    @Schema(description = "事件编号")
    private Long id;

    @Schema(description = "运输订单编号")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "事件类型名")
    private String eventTypeName;

    @Schema(description = "事件时间")
    private LocalDateTime eventTime;

    @Schema(description = "操作人")
    private String operator;

    @Schema(description = "事件详情")
    private String detail;

    @Schema(description = "扩展数据JSON")
    private String extraData;

}
