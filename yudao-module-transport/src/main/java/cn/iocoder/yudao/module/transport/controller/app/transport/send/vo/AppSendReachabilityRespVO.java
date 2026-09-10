package cn.iocoder.yudao.module.transport.controller.app.transport.send.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户 APP - 当前位置可达性评估 Response VO。
 *
 * 业务含义：用户在小程序里用"当前位置"寄货时，系统先判断车辆能否直接到达该位置；
 * 不可达（校园/步行区/无道路等）→ 推荐最近可服务站点 + 步行距离与时间，由用户确认送站。
 * 订单会同时保存"用户原始地址/坐标"与"实际服务站"，两者不互相覆盖。
 */
@Schema(description = "用户 APP - 当前位置可达性评估 Response VO")
@Data
public class AppSendReachabilityRespVO {

    @Schema(description = "当前位置是否可以由车辆直接服务（true=可就近上门；false=需送站）")
    private Boolean reachable;

    @Schema(description = "不可达原因码（可空）：USER_LOCATION_UNREACHABLE 当前位置不适合车辆进入 / NO_SAFE_HANDOFF_POINT 附近无可用站点")
    private String reasonCode;

    @Schema(description = "取货服务方式（ServiceModeEnum）：NEAREST_STATION 最近站点交接 / DOOR_PICKUP 上门交接")
    private String serviceMode;

    @Schema(description = "到推荐站点的距离(km)")
    private BigDecimal distanceKm;

    @Schema(description = "预计步行分钟数（按 4.3km/h 估算）")
    private Integer walkMinutes;

    @Schema(description = "距离来源：AMAP_ROAD 高德道路 / HAVERSINE 直线估算")
    private String distanceProvider;

    @Schema(description = "推荐服务站（不可达时用户前往；可达时作为就近交接站）")
    private RecommendedStation recommendedStation;

    @Schema(description = "给用户看的提示文案")
    private String message;

    @Schema(description = "推荐服务站点")
    @Data
    public static class RecommendedStation {
        @Schema(description = "站点编号")
        private Long id;
        @Schema(description = "站点名称")
        private String name;
        @Schema(description = "经度(GCJ-02)")
        private Double longitude;
        @Schema(description = "纬度(GCJ-02)")
        private Double latitude;
        @Schema(description = "到用户当前位置的距离(km)")
        private BigDecimal distanceKm;
    }
}
