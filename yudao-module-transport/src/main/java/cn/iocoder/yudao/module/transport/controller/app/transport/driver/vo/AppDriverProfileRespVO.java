package cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "用户 APP - 司机档案 Response VO")
@Data
public class AppDriverProfileRespVO {

    @Schema(description = "司机编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long driverId;
    @Schema(description = "司机姓名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(description = "联系电话")
    private String mobile;
    @Schema(description = "绑定车辆编号")
    private Long vehicleId;
    @Schema(description = "车牌号")
    private String plateNo;
    @Schema(description = "货仓件数上限（运力）")
    private Integer cargoCapacity;
    @Schema(description = "载货重量上限(kg)")
    private Integer cargoCapacityKg;
}
