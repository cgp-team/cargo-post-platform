package cn.iocoder.yudao.module.transport.service.geo;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 线路走廊服务（RouteCorridorService）：把"站间怎么走"落回**公交线路本身的真实走向**。
 *
 * <p>业务问题：站点在道路两侧（同名站分上行/下行）、或该点位需要下穿/上桥时，
 * 用"点对点驾车规划"取站间轨迹会得到"进隧道 → 绕远 → 掉头"的走法，
 * 与司机实际按线路行驶的路径不一致（用户反馈：文峰公社 → 七公里 应先经文峰正街路口再到吉祥路口）。</p>
 *
 * <p>做法：优先用线路已落库的整条线真实几何（{@code transport_route.navigation_polyline}，
 * 由"逐站 waypoints"取到，代表这条线的真实走廊），再按起讫站最近的顶点切片；
 * 库里没有就按线路站序现取一次并落库。取不到时返回 {@code null}，由调用方回退原点对点逻辑。</p>
 */
@Slf4j
@Service
public class RouteCorridorService {

    @Resource private RouteMapper routeMapper;
    @Resource private RouteStationMapper routeStationMapper;
    @Resource private StationMapper stationMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private RoadPolylineService roadPolylineService;

    /** 车辆当前运营线路编号（取绑定 ID 最小的一条；未绑定返回 null） */
    public Long operatingRouteId(Long vehicleId) {
        if (vehicleId == null || driverVehicleMapper == null) {
            return null;
        }
        return driverVehicleMapper.selectActiveBindings().stream()
                .filter(b -> Objects.equals(b.getVehicleId(), vehicleId) && b.getRouteId() != null)
                .min(Comparator.comparing(b -> b.getId() == null ? Long.MAX_VALUE : b.getId()))
                .map(DriverVehicleDO::getRouteId)
                .orElse(null);
    }

    /**
     * 沿车辆运营线路取两站之间的真实道路轨迹。
     * 车辆没有运营线路、或两站不在这条线路上时返回 {@code null}（调用方回退点对点）。
     */
    public List<double[]> alongOperatingLine(Long vehicleId, Long fromStationId, Long toStationId) {
        Long routeId = operatingRouteId(vehicleId);
        return routeId == null ? null : alongRoute(routeId, fromStationId, toStationId);
    }

    /** 沿指定线路取两站之间的真实道路轨迹；线路不可用/两站不在线路上返回 null */
    public List<double[]> alongRoute(Long routeId, Long fromStationId, Long toStationId) {
        if (routeId == null || fromStationId == null || toStationId == null || routeStationMapper == null) {
            return null;
        }
        List<Long> lineStations = routeStationMapper.selectListByRouteId(routeId).stream()
                .sorted(Comparator.comparing(RouteStationDO::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(RouteStationDO::getStationId)
                .filter(Objects::nonNull)
                .toList();
        // 站点口径：两站都必须在这条线路上（否则切片没有意义）
        if (!lineStations.contains(fromStationId) || !lineStations.contains(toStationId)) {
            return null;
        }
        List<double[]> corridor = corridor(routeId, lineStations);
        if (corridor == null) {
            return null;
        }
        StationDO from = stationMapper.selectById(fromStationId);
        StationDO to = stationMapper.selectById(toStationId);
        if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                || to.getLongitude() == null || to.getLatitude() == null) {
            return null;
        }
        return roadPolylineService.sliceAlong(corridor,
                from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
    }

    /**
     * 线路整条真实走廊：库里有（且来源 AMAP）直接用；没有就按站序逐站 waypoints 取一次并落库。
     * 高德不可用时返回 {@code null}（不伪造几何）。
     */
    public List<double[]> corridor(Long routeId, List<Long> stationIdsInOrder) {
        if (routeId == null || routeMapper == null || roadPolylineService == null) {
            return null;
        }
        RouteDO route = routeMapper.selectById(routeId);
        if (route != null && StrUtil.isNotBlank(route.getNavigationPolyline())
                && "AMAP".equalsIgnoreCase(route.getNavigationSource())) {
            List<double[]> stored = parse(route.getNavigationPolyline());
            if (stored.size() >= 2) {
                return stored;
            }
        }
        if (!roadPolylineService.available() || stationIdsInOrder == null || stationIdsInOrder.size() < 2) {
            return null;
        }
        List<double[]> stops = new ArrayList<>();
        for (Long stationId : stationIdsInOrder) {
            StationDO station = stationMapper.selectById(stationId);
            if (station != null && station.getLongitude() != null && station.getLatitude() != null) {
                stops.add(new double[]{station.getLongitude().doubleValue(), station.getLatitude().doubleValue()});
            }
        }
        if (stops.size() < 2) {
            return null;
        }
        List<double[]> through = roadPolylineService.routeThrough(stops);
        if (through == null || through.size() < 2) {
            return null;
        }
        persist(routeId, through);
        return through;
    }

    /** 线路走廊轨迹落库（幂等，失败只记日志）：取一次即可长期复用，避免反复占用高德配额 */
    private void persist(Long routeId, List<double[]> points) {
        try {
            RouteDO update = new RouteDO();
            update.setId(routeId);
            update.setNavigationPolyline(serialize(points));
            update.setNavigationSource("AMAP");
            routeMapper.updateById(update);
        } catch (Exception ex) {
            log.warn("[corridor] 线路 {} 真实走廊落库失败：{}", routeId, ex.getMessage());
        }
    }

    /** 解析 "lon,lat;lon,lat;..."；非法返回空列表 */
    public static List<double[]> parse(String polyline) {
        List<double[]> points = new ArrayList<>();
        if (StrUtil.isBlank(polyline)) {
            return points;
        }
        for (String pair : polyline.split(";")) {
            int comma = pair.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            try {
                points.add(new double[]{Double.parseDouble(pair.substring(0, comma).trim()),
                        Double.parseDouble(pair.substring(comma + 1).trim())});
            } catch (NumberFormatException ignored) {
                return List.of(); // 脏数据：宁可不用走廊，也不要按错误几何画线
            }
        }
        return points;
    }

    /** 序列化 "lon,lat;lon,lat;..."（与 parse 对称） */
    public static String serialize(List<double[]> points) {
        StringBuilder sb = new StringBuilder(points.size() * 16);
        for (double[] p : points) {
            if (p == null || p.length < 2) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f", p[0]))
                    .append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.6f", p[1]));
        }
        return sb.toString();
    }
}
