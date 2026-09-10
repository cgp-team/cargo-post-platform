package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 站点精简信息 Response VO")
@Data
public class StationSimpleRespVO {
    @Schema(description = "站点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;
    @Schema(description = "站点编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String stationCode;
    @Schema(description = "站点名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String stationName;
    @Schema(description = "站点地址")
    private String address;
    // 坐标：管理端调度结果可视化（地图画经停点/线路）直接用精简列表，避免再拉一次分页接口
    @Schema(description = "经度(GCJ-02)")
    private BigDecimal longitude;
    @Schema(description = "纬度(GCJ-02)")
    private BigDecimal latitude;
}
