package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 司机车辆绑定 Response VO")
@Data
public class DriverVehicleRespVO {

    @Schema(description = "绑定编号")
    private Long id;

    @Schema(description = "司机编号")
    private Long driverId;

    @Schema(description = "司机姓名")
    private String driverName;

    @Schema(description = "车辆编号")
    private Long vehicleId;

    @Schema(description = "车牌号")
    private String plateNo;

    @Schema(description = "绑定时间")
    private LocalDateTime bindTime;

    @Schema(description = "解绑时间")
    private LocalDateTime unbindTime;

    @Schema(description = "绑定状态：1 绑定中，0 已解绑")
    private Integer status;

}
