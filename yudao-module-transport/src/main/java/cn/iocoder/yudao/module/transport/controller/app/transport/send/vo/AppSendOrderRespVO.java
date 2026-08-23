package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 寄货订单 Response VO")
@Data
public class AppSendOrderRespVO {

    @Schema(description = "订单编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "业务订单号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String orderNo;
    @Schema(description = "订单类型：1客运 2货运 3邮快件")
    private Integer orderType;
    @Schema(description = "订单状态(0待调度 1已入池 2已分配 3已发车 4已完成 5已取消)")
    private Integer status;
    @Schema(description = "订单状态名")
    private String statusName;
    @Schema(description = "快递单号（邮快件）")
    private String mailNo;
    @Schema(description = "取件码（邮快件，6位数字）")
    private String pickupCode;
    @Schema(description = "货运审核状态：0待审核 1已通过 2已拒绝")
    private Integer auditStatus;
    @Schema(description = "拒绝原因（审核拒绝时）")
    private String rejectReason;
    @Schema(description = "货物名称")
    private String goodsName;
    @Schema(description = "货物重量(kg)")
    private BigDecimal goodsWeight;
    @Schema(description = "货物备注")
    private String goodsNote;
    @Schema(description = "货物照片")
    private String photoUrl;
    @Schema(description = "收货人")
    private String receiverName;
    @Schema(description = "收货电话")
    private String receiverMobile;
    @Schema(description = "收货地址")
    private String receiverAddress;
    @Schema(description = "下单时间")
    private LocalDateTime createTime;

    // ========== 到达预估（仅 track 详情填充，未分配时全为 null） ==========
    @Schema(description = "承运车牌号")
    private String vehiclePlate;
    @Schema(description = "承运班次编码")
    private String shiftCode;
    @Schema(description = "目标站点名称（送达方向经停站）")
    private String targetStation;
    @Schema(description = "预计到达时间")
    private LocalDateTime estimatedArrivalTime;
    @Schema(description = "预计到达剩余分钟数（仅未来时间有值）")
    private Integer etaMinutes;
}
