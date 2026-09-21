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
    @Schema(description = "订单状态(0已创建 1已入池 2已分配 3已发车 4已完成 5已取消 6待审核 7待客户操作 8待入池)")
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
    @Schema(description = "承运审核结果(ReviewStatusEnum)：0待审 1通过 2需客户操作 3需人工 4拒运")
    private Integer reviewStatus;
    @Schema(description = "承运审核结果名")
    private String reviewStatusName;
    @Schema(description = "承运审核原因码(ReviewReasonCodeEnum，逗号分隔，前端映射文案)")
    private String reviewReasonCodes;
    @Schema(description = "取货服务方式(ServiceModeEnum)")
    private String pickupServiceMode;
    @Schema(description = "送达服务方式(ServiceModeEnum)")
    private String deliveryServiceMode;
    @Schema(description = "建议服务站点编号(替代交接推荐)")
    private Long servicePointStationId;
    @Schema(description = "建议服务站点名")
    private String servicePointStationName;
    @Schema(description = "建议服务站点经度（客户送站导航用）")
    private Double servicePointLongitude;
    @Schema(description = "建议服务站点纬度")
    private Double servicePointLatitude;
    @Schema(description = "取货站点到建议服务站点的直线距离(km)：前端提示\"就近前往\"")
    private Double servicePointDistanceKm;
    @Schema(description = "货物名称")
    private String goodsName;
    @Schema(description = "货物重量(kg)")
    private BigDecimal goodsWeight;
    @Schema(description = "货物类型（农产品/生鲜果蔬/日用品/文件票据/其他）")
    private String cargoCategory;
    @Schema(description = "货物件数")
    private Integer itemCount;
    @Schema(description = "货物体积(m³)")
    private BigDecimal volumeM3;

    @Schema(description = "订单金额(元)：寄货页试算口径（件单价×件数 + 里程费）")
    private BigDecimal totalAmount;

    @Schema(description = "是否生鲜/需冷链")
    private Boolean freshFlag;
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
    @Schema(description = "用户原始寄货地址（如 重庆邮电大学）")
    private String originalAddress;
    @Schema(description = "用户原始纬度(GCJ-02)")
    private BigDecimal originalLatitude;
    @Schema(description = "用户原始经度(GCJ-02)")
    private BigDecimal originalLongitude;
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

    // ========== 车来取货/送货提醒（承运车辆实时位置，未发车/未上报时为 null） ==========
    @Schema(description = "承运车辆最新经度(GCJ-02)")
    private Double carrierLongitude;
    @Schema(description = "承运车辆最新纬度(GCJ-02)")
    private Double carrierLatitude;
    @Schema(description = "承运车辆距目标站点公里数（Haversine）")
    private BigDecimal carrierDistanceKm;
    @Schema(description = "承运车辆预计到达目标站点分钟数（实时位置估算）")
    private Integer carrierEtaMinutes;
    @Schema(description = "承运车辆位置来源：REAL_FRESH 真实上报 / REAL_STALE 位置可能过期")
    private String carrierLocationSource;
    @Schema(description = "车辆是否即将到站（距目标站点 <= 10 分钟）：前端据此高亮并提示\"车快到了\"")
    private Boolean carrierApproaching;

    // ========== 司机到站/作业进度（后端为源：派单经停明细状态；未派单时为 null） ==========
    @Schema(description = "司机是否已到达本单交接站点（已到达/已完成作业），前端提示\"司机已到达\"")
    private Boolean carrierArrived;
    @Schema(description = "司机到达（或完成交接作业）的站点名")
    private String carrierArrivedStation;
    @Schema(description = "司机作业状态名：待执行/行驶中/已到站/揽收中/派送中/已完成")
    private String carrierTaskStatus;
    @Schema(description = "到达/作业状态更新时间（经停明细 update_time，近似现场时间）")
    private LocalDateTime carrierArrivedTime;
    @Schema(description = "司机已装车（揽收完成）")
    private Boolean carrierLoaded;
    @Schema(description = "司机已妥投（派送完成）")
    private Boolean carrierDelivered;
    @Schema(description = "承运司机姓名")
    private String driverName;
    @Schema(description = "承运司机电话（现场联系用）")
    private String driverMobile;
}
