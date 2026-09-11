package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;

@Schema(description="Station Base VO")
@Data
public class StationBaseVO {
    @Schema(description="station code")
    private String stationCode;

    @Schema(description="station name")
    private String stationName;

    @Schema(description="station level")
    private Integer stationLevel;

    @Schema(description="longitude")
    private BigDecimal longitude;

    @Schema(description="latitude")
    private BigDecimal latitude;

    @Schema(description="address")
    private String address;

    @Schema(description = "站点状态：0=启用 1=停用（寄货选站/路线预览/下单会拦截停用站点）")
    private Integer status;

    @Schema(description = "数据来源：REAL 现实公交站 / PROJECT 项目自建站 / SIMULATION 模拟站")
    private String sourceType;

    @Schema(description = "站点类型：BUS_STOP 公交站 / CARGO_STATION 货运站 / MIXED 混合")
    private String stationType;

    @Schema(description = "用户可达：能否推荐给用户作为送站/取货点")
    private Boolean userAccess;

    @Schema(description = "车辆可达：车辆能否进入装卸货（校园禁行区置 false，站点启用不代表车辆能进）")
    private Boolean vehicleAccess;

    @Schema(description = "是否可用于调度（作场站/换乘站）")
    private Boolean dispatchEnabled;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;
}
