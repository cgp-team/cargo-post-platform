package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - 车辆精简信息 Response VO")
@Data
public class VehicleSimpleRespVO {
    @Schema(description = "车辆编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "车牌号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String plateNo;
    // 绑定司机：发货/派单时选车就能看到"这车谁开"，避免派给没有司机的车（司机端看不到任务）
    @Schema(description = "当前绑定司机姓名（未绑定为 null）")
    private String driverName;
    @Schema(description = "当前绑定司机电话")
    private String driverMobile;
}
