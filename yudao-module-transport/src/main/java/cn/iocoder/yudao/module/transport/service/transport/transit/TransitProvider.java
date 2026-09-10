package cn.iocoder.yudao.module.transport.service.transport.transit;

import java.util.List;

/**
 * 附近公交数据源抽象（统一 Transit API）。
 *
 * 分两层，各自标注来源，绝不互相伪装：
 * - {@code REAL_TRANSIT}：真实地图/公交数据源（高德 AMAP），提供"我附近真实有哪些公交站/线路"；
 * - {@code PROJECT_TRANSIT}：项目自建客货邮站点/线路（transport_station / transport_route）。
 *
 * 实现必须容错：外部不可用（未配 key、超时、限额）时 {@link #available()} 返回 false，
 * 由调用方降级到下一层，不能抛出异常影响主流程。
 *
 * 坐标约定：入参与返回坐标统一为 **GCJ-02**（与站点表、wx.getLocation(gcj02)、高德一致），
 * 需要转换时走 {@code GeoCoordUtil}。
 */
public interface TransitProvider {

    /** 数据源标识：高德现实公交 */
    String REAL_TRANSIT = "REAL_TRANSIT";
    /** 数据源标识：项目自建客邮线路 */
    String PROJECT_TRANSIT = "PROJECT_TRANSIT";

    /** 数据源名：AMAP / PROJECT */
    String name();

    /**
     * 该数据源产出的站点/线路属于哪一层：{@link #REAL_TRANSIT}（现实公交）或 {@link #PROJECT_TRANSIT}（项目自建）。
     * 注意与 {@link #name()} 区分：name 是展示用的数据源名（AMAP/PROJECT），layer 是分层标识。
     */
    String dataSource();

    /** 当前是否可用（未配置 key、依赖缺失均返回 false，调用方据此降级） */
    boolean available();

    /** 查询附近公交站点（radius 单位：米） */
    List<TransitStation> searchNearbyStations(double latitude, double longitude, double radiusMeters);

    /** 查询附近公交线路（可选能力：数据源不支持时返回空列表） */
    default List<TransitLine> searchNearbyLines(double latitude, double longitude, double radiusMeters) {
        return List.of();
    }

    /** 附近站点（坐标为 GCJ-02） */
    record TransitStation(String name, double longitude, double latitude, Double distanceKm,
                          List<String> lines, String address, String dataSource) {
    }

    /** 附近线路 */
    record TransitLine(String routeName, String startStation, String endStation, String dataSource) {
    }
}
