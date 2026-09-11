package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 司机交接任务 Response VO")
@Data
public class AppDriverHandoverRespVO {

    @Schema(description = "交接记录编号")
    private Long id;

    @Schema(description = "运输订单编号")
    private Long orderId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "货物名称")
    private String goodsName;

    @Schema(description = "换乘站编号")
    private Long stationId;

    @Schema(description = "换乘站名称")
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

    @Schema(description = "备注")
    private String remark;

}
