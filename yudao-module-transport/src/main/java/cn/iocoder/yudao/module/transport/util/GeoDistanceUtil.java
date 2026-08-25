package cn.iocoder.yudao.module.transport.util;

import java.util.List;

/**
 * 地理距离工具：Haversine 大圆距离 + 按平均时速估算行驶分钟 + polyline 偏航检测。
 *
 * 用于两处：
 * 1. "车来取货/送货"实时到站提醒（承运车辆距目标站点公里/分钟，见 AppSendController）；
 * 2. 调度方案总里程换算（算法返回经纬度欧氏距离(度) → 按经停站点坐标换算真实公里，见 DispatchServiceImpl）；
 * 3. 司机偏航检测（车辆坐标距规划 polyline 最小距离，>阈值标记 ROUTE_DEVIATED，见 Phase 9）。
 *
 * GCJ-02 坐标相对 WGS-84 偏移约百米级，对公里级里程计算影响可忽略。
 */
public final class GeoDistanceUtil {

    /** 地球平均半径（km） */
    public static final double EARTH_RADIUS_KM = 6371.0;

    /** 乡村班线平均时速（km/h），"车来取货/送货"实时到站分钟估算默认值 */
    public static final double DEFAULT_AVG_SPEED_KMH = 25.0;

    /** 偏航阈值（米）：车辆距规划 polyline 超过该值标记 ROUTE_DEVIATED（Phase 9 只报警不自动改方案） */
    public static final double DEVIATION_THRESHOLD_METERS = 100.0;

    private GeoDistanceUtil() {
    }

    /**
     * 点到 polyline 的最小距离（米），偏航检测用。逐段计算点到线段最短距离取最小。
     * polyline 为空/单点无法判定时返回 -1。
     */
    public static double deviationMetersFromPolyline(double lon, double lat, List<double[]> polyline) {
        if (polyline == null || polyline.size() < 2) {
            return -1;
        }
        double min = Double.MAX_VALUE;
        for (int i = 1; i < polyline.size(); i++) {
            double[] a = polyline.get(i - 1);
            double[] b = polyline.get(i);
            double d = pointToSegmentMeters(lon, lat, a[0], a[1], b[0], b[1]);
            if (d < min) {
                min = d;
            }
        }
        return min;
    }

    /** 点到线段最短距离（米）：先在经纬度平面投影到线段，再按局部尺度换算米（偏航 100m 粗判够用） */
    private static double pointToSegmentMeters(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        double t;
        if (lenSq < 1e-12) {
            t = 0;
        } else {
            t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
            t = Math.max(0, Math.min(1, t));
        }
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        double latRad = Math.toRadians((py + projY) / 2);
        double meterPerLon = 111320 * Math.cos(latRad);
        double xMeters = (projX - px) * meterPerLon;
        double yMeters = (projY - py) * 111320;
        return Math.sqrt(xMeters * xMeters + yMeters * yMeters);
    }

    /** Haversine 大圆距离（km） */
    public static double haversineKm(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
    }

    /** 按平均时速估算行驶分钟，至少 1 分钟（平均时速非法时按 1 分钟兜底） */
    public static int estimateMinutes(double distKm, double avgSpeedKmh) {
        if (avgSpeedKmh <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.round(distKm / avgSpeedKmh * 60));
    }

    /** 两点距离 + 到站分钟；任一坐标为 null 时返回 null（车辆未发车/坐标缺失） */
    public static DistanceEta computeKmAndMinutes(Double lon1, Double lat1, Double lon2, Double lat2,
                                                  double avgSpeedKmh) {
        if (lon1 == null || lat1 == null || lon2 == null || lat2 == null) {
            return null;
        }
        double distKm = haversineKm(lon1, lat1, lon2, lat2);
        return new DistanceEta(distKm, estimateMinutes(distKm, avgSpeedKmh));
    }

    /** 距离（km）+ 到站分钟 结果 */
    public record DistanceEta(double distKm, int etaMinutes) {
    }
}
