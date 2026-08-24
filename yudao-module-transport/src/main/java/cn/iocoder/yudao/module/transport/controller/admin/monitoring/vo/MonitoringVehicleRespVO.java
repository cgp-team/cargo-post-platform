package cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 监控车辆实时位置 Response VO")
@Data
public class MonitoringVehicleRespVO {

    @Schema(description = "车辆编号")
    private Long vehicleId;

    @Schema(description = "车牌号")
    private String plateNo;

    @Schema(description = "司机姓名（未绑定时为空）")
    private String driverName;

    @Schema(description = "监控状态：0 空闲，1 在途，2 停用")
    private Integer status;

    @Schema(description = "经度（无排班且非在途时为空）")
    private Double longitude;

    @Schema(description = "纬度（无排班且非在途时为空）")
    private Double latitude;

    @Schema(description = "当前班次编码")
    private String shiftCode;

    @Schema(description = "当前线路名称")
    private String routeName;

    @Schema(description = "班次进度百分比 0-100（仅在途时有值）")
    private Integer progress;

    @Schema(description = "下一站名称（仅在途且未到终点时有值）")
    private String nextStationName;

    @Schema(description = "估算速度(km/h，按线路里程与计划时长估算，仅在途时有值)")
    private Double speedKmh;

    @Schema(description = "位置数据来源：REAL=司机5分钟内上报真实位置 / SIMULATED=按班次计划时间模拟插值")
    private String dataSource;

    @Schema(description = "最后位置时间（司机真实上报时间；模拟车辆为 null）")
    private LocalDateTime lastLocationTime;
}
