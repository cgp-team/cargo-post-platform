package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "用户 APP - 寄货创建 Request VO")
@Data
public class AppSendOrderCreateReqVO {

    @Schema(description = "取货站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "取货站点不能为空")
    private Long pickupStationId;

    @Schema(description = "送达站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "送达站点不能为空")
    private Long deliveryStationId;

    @Schema(description = "货物名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "货物名称不能为空")
    private String goodsName;

    @Schema(description = "货物重量(kg)", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "货物重量不能为空")
    private BigDecimal goodsWeight;

    @Schema(description = "货物类型（如：农产品/生鲜果蔬/日用品/文件票据/其他），缺省按农产品")
    private String cargoCategory;

    @Schema(description = "货物件数，缺省 1")
    @Min(value = 1, message = "货物件数不能小于 1")
    private Integer itemCount;

    @Schema(description = "货物体积(m³)，缺省 0")
    private BigDecimal volumeM3;

    @Schema(description = "是否生鲜/需冷链（true 时承运审核转人工确认），缺省 false")
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

    // ========== 用户原始位置（"位置 ≠ 车辆能到的地方"：原始地址与服务站分开保存，不互相覆盖） ==========

    @Schema(description = "用户原始寄货地址（如 重庆邮电大学；高德逆地理结果或用户填写）")
    private String originalAddress;

    @Schema(description = "用户原始纬度(GCJ-02)")
    private BigDecimal originalLatitude;

    @Schema(description = "用户原始经度(GCJ-02)")
    private BigDecimal originalLongitude;

    @Schema(description = "取货服务方式（ServiceModeEnum.code）：小程序用当前位置寄货时由可达性评估给出"
            + "（NEAREST_STATION 最近站点交接 / DOOR_PICKUP 上门）；不传则由承运审核推导")
    private String pickupServiceMode;

    @Schema(description = "最早取货时间")
    private LocalDateTime earliestPickupTime;
}
