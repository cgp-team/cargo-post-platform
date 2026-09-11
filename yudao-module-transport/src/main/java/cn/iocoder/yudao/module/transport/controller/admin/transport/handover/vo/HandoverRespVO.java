package cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 货物交接 Response VO")
@Data
public class HandoverRespVO {

    @Schema(description = "交接编号")
    private Long id;

    @Schema(description = "运输订单编号")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "来源运输段编号")
    private Long legFromId;

    @Schema(description = "目的运输段编号")
    private Long legToId;

    @Schema(description = "交接站点编号")
    private Long stationId;

    @Schema(description = "交接站点名称")
    private String stationName;

    @Schema(description = "交出司机编号")
    private Long fromDriverId;

    @Schema(description = "交出司机姓名")
    private String fromDriverName;

    @Schema(description = "接收司机编号")
    private Long toDriverId;

    @Schema(description = "接收司机姓名")
    private String toDriverName;

    @Schema(description = "交接件数")
    private Integer itemCount;

    @Schema(description = "交接重量(kg)")
    private BigDecimal weightKg;

    @Schema(description = "交接照片URL")
    private String photoUrl;

    @Schema(description = "状态：0待确认 1已确认 2有争议")
    private Integer status;

    @Schema(description = "状态名")
    private String statusName;

    @Schema(description = "交接时间")
    private LocalDateTime handoverTime;

    @Schema(description = "确认时间")
    private LocalDateTime confirmTime;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
