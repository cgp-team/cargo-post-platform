package cn.iocoder.yudao.module.transport.service.transport.bus;

import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusLineRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusNearbyRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusRespVO;

import java.util.List;

/**
 * 小程序端实时公交 Service。
 * 复用监控中心实时车辆位置（司机真实上报优先，否则按班次时刻插值），
 * 聚合线路起终点 / 下一站 / ETA 供小程序「附近公交 · 实时到站」展示。
 */
public interface AppBusService {

    /** 实时公交列表（有班次的车辆；在途为行驶中，空闲为已到站/待发） */
    List<AppBusRespVO> getRealtimeBuses();

    /** 实时公交线路（含经停点与该线在线车辆，供「车来了式」地图+列表页） */
    /**
     * 实时公交线路（含经停点与在线车辆）。
     *
     * @param latitude  用户纬度；与 longitude 同时给出时只返回该点附近（radius 米内）的线路，
     *                  避免主城几百条线路全量下发导致小程序超时；为空则返回全部（内部有上限保护）
     * @param longitude 用户经度
     * @param radius    附近半径（米），为空默认 15km
     */
    List<AppBusLineRespVO> getLines(Double latitude, Double longitude, Double radius);

    /** 全量线路（内部上限保护），兼容旧调用 */
    default List<AppBusLineRespVO> getLines() {
        return getLines(null, null, null);
    }

    /**
     * 单条线路的真实道路轨迹（点开线路时按需查询，带 5 分钟缓存）。
     *
     * <p>不放进 {@link #getLines()}：真实公交换乘线网有几十条线、每条 20~40 站，
     * 逐段调用高德路网会瞬时打出几百次上游请求，导致小程序 10s 超时。</p>
     */
    List<AppBusLineRespVO.RoadPoint> getLinePolyline(Long routeId);

    /**
     * 附近实时公交：按用户坐标 Haversine 直线过滤 radius 内车辆与站点（第一版不调高德路网）。
     *
     * @param latitude  用户纬度（GCJ-02；无精确位置可传 null）
     * @param longitude 用户经度（GCJ-02；无精确位置可传 null）
     * @param radius    筛选半径(米)，默认 5000
     * @param district  无精确位置时的区域名 fallback（按站点名称/地址模糊匹配）
     */
    AppBusNearbyRespVO getNearbyBuses(Double latitude, Double longitude, Double radius, String district);

}
