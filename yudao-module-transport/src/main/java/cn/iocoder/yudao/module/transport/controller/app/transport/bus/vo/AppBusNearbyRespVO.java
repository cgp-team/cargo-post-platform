package cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "用户 APP - 附近实时公交 Response VO")
@Data
public class AppBusNearbyRespVO {

    /** 状态：运行中 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 状态：空闲停靠 */
    public static final String STATUS_IDLE = "IDLE";
    /** 状态：已到终点 */
    public static final String STATUS_ARRIVED = "ARRIVED";
    /** 状态：无位置 */
    public static final String STATUS_NO_LOCATION = "NO_LOCATION";
    /** 数据来源：真实司机上报 */
    public static final String SOURCE_REAL = "REAL";
    /** 整体数据来源：无车辆 */
    public static final String SOURCE_NONE = "NONE";

    @Schema(description = "radius 内实时车辆（按距用户直线距离升序）")
    private List<NearbyBus> buses;

    @Schema(description = "radius 内附近站点（按距离升序）")
    private List<NearbyStation> nearbyStations;

    @Schema(description = "最近站点")
    private NearbyStation nearestStation;

    @Schema(description = "附近站点关联线路（无运营车辆也返回，供前端展示\"该区域有哪些线路/不在运营\"）")
    private List<NearbyLine> lines;

    @Schema(description = "整体数据来源：REAL（含真实车辆）/ NONE")
    private String dataSource;

    @Schema(description = "定位级别：PRECISE / APPROXIMATE / DISTRICT / UNKNOWN")
    private String locationLevel;

    @Schema(description = "是否按精确坐标筛选（true=有经纬度；false=district 区域 fallback）")
    private Boolean located;

    // ========== 分层数据源标识（现实公交 / 项目自建 / 模拟）==========

    @Schema(description = "现实公交数据源是否可用（配置 AMAP_KEY 且调用成功）；false 时只展示项目自建线路与模拟车辆")
    private Boolean realTransitAvailable;

    @Schema(description = "现实公交数据源名：AMAP / NONE")
    private String transitProvider;

    @Schema(description = "现实公交站点数（REAL_TRANSIT）")
    private Integer realStationCount;

    @Schema(description = "项目自建站点数（PROJECT_TRANSIT）")
    private Integer projectStationCount;

    @Schema(description = "附近线路条数（去重，含现实与项目线路；首页\"附近有 N 条公交线路\"用）")
    private Integer lineCount;

    @Schema(description = "当前是否有车辆在运营时段内（false 时前端如实展示\"当前不在运营时间\"）")
    private Boolean inService;

    @Schema(description = "下一班发车时间（HH:mm；附近线路当日无可发班次时为空）")
    private String nextDepartureTime;

    @Schema(description = "下一班发车班次编码")
    private String nextDepartureShiftCode;

    @Schema(description = "服务时间说明（如 06:30–22:30）")
    private String serviceWindowText;

    @Schema(description = "附近实时车辆")
    @Data
    public static class NearbyBus {

        @Schema(description = "车辆编号")
        private Long busId;

        @Schema(description = "车牌号")
        private String plateNo;

        @Schema(description = "班次编码")
        private String shiftCode;

        @Schema(description = "线路名称")
        private String routeName;

        @Schema(description = "起点站")
        private String startStation;

        @Schema(description = "终点站")
        private String endStation;

        @Schema(description = "状态：RUNNING / IDLE / ARRIVED / NO_LOCATION")
        private String status;

        @Schema(description = "下一站名称（无可靠位置时为 null，前端不显示'—'）")
        private String nextStation;

        @Schema(description = "经度")
        private Double longitude;

        @Schema(description = "纬度")
        private Double latitude;

        @Schema(description = "位置数据来源：REAL=真实上报 / REAL_STALE=上报已过期")
        private String dataSource;

        @Schema(description = "位置新鲜度：REAL_FRESH(5min内真实) / REAL_STALE / NO_LOCATION")
        private String locationSource;

        @Schema(description = "当前站点（无可靠判断为 null）")
        private String currentStation;

        @Schema(description = "距下一站道路距离(km，高德路网；无可靠位置为 null)")
        private Double distanceToNextStationKm;

        @Schema(description = "预计到站分钟（高德路网 duration 向上取整；无可靠位置为 null）")
        private Integer etaMinutes;

        @Schema(description = "待发车时距发车分钟数（未在途时给出；前端显示“N 分钟后发车”）")
        private Integer waitDepartureMinutes;

        @Schema(description = "路网来源：AMAP=高德真实 / EUCLIDEAN=直线估算")
        private String routeProvider;

        @Schema(description = "最后位置时间（司机真实上报时间；模拟车辆为 null）")
        private LocalDateTime lastLocationTime;

        @Schema(description = "数据生成时间")
        private LocalDateTime updatedAt;

        @Schema(description = "距用户直线距离(km)")
        private Double distanceKm;

        @Schema(description = "距用户最近、且本车还会经过的站点名（用户在这一站等车最方便）")
        private String nearestStationName;

        @Schema(description = "预计到达「用户最近站点」的分钟数（在途且该站仍在前方时给出；待发/已过站为 null）")
        private Integer nearestStationEtaMinutes;

        @Schema(description = "距离「用户最近站点」还有几站（不含当前所处区间）")
        private Integer stopsToNearestStation;

        @Schema(description = "「用户最近站点」距用户直线距离(km)")
        private Double nearestStationDistanceKm;

    }

    @Schema(description = "附近站点关联线路")
    @Data
    public static class NearbyLine {

        @Schema(description = "线路名称")
        private String routeName;

        @Schema(description = "起点站")
        private String startStation;

        @Schema(description = "终点站")
        private String endStation;

        @Schema(description = "数据来源：REAL_TRANSIT 现实公交 / PROJECT_TRANSIT 项目自建客货邮线路")
        private String dataSource;

    }

    @Schema(description = "附近站点")
    @Data
    public static class NearbyStation {

        @Schema(description = "站点编号")
        private Long id;

        @Schema(description = "站点名称")
        private String name;

        @Schema(description = "经度")
        private Double longitude;

        @Schema(description = "纬度")
        private Double latitude;

        @Schema(description = "距用户直线距离(km)")
        private Double distanceKm;

        @Schema(description = "数据来源：REAL_TRANSIT 现实公交站点 / PROJECT_TRANSIT 项目自建站点")
        private String dataSource;

        @Schema(description = "途经线路名（现实站点可能为空：高德周边搜索不返回线路）")
        private List<String> lines;

    }
}
