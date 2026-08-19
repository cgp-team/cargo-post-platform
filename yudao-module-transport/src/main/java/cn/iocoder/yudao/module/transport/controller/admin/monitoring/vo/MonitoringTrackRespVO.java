package cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 车辆历史轨迹 Response VO")
@Data
public class MonitoringTrackRespVO {

    @Schema(description = "车辆编号")
    private Long vehicleId;

    @Schema(description = "车牌号")
    private String plateNo;

    @Schema(description = "轨迹点列表（按上报时间升序，当日无在途轨迹时为空）")
    private List<TrackPoint> points;

    @Schema(description = "管理后台 - 车辆历史轨迹点")
    @Data
    public static class TrackPoint {

        @Schema(description = "经度（司机端上报原始坐标）")
        private Double longitude;

        @Schema(description = "纬度（司机端上报原始坐标）")
        private Double latitude;

        @Schema(description = "速度(km/h)")
        private Double speedKmh;

        @Schema(description = "上报时间")
        private LocalDateTime reportTime;

        @Schema(description = "所属班次编号")
        private Long shiftId;
    }
}
