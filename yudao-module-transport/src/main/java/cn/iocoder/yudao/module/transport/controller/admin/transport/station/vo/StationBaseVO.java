package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
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
    @Schema(description = "揽收点联系人（空=没填）", example = "王强")
    @Size(max = 64, message = "联系人长度不能超过 64")
    private String contactName;
    @Schema(description = "揽收点联系电话", example = "13900001003")
    @Size(max = 32, message = "联系电话长度不能超过 32")
    private String contactMobile;
    @Schema(description = "营业时间（自由文本，空=未公布）", example = "08:00-18:00")
    @Size(max = 64, message = "营业时间长度不能超过 64")
    private String businessHours;

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
    @Schema(description = "是否电商站点")
    private Boolean ecommerceFlag;

    @Schema(description = "排序")
    private Integer sort;

    @Schema(description = "备注")
    private String remark;
}
