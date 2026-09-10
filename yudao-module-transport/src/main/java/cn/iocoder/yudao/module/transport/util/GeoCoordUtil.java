package cn.iocoder.yudao.module.transport.util;

/**
 * 坐标系转换工具（唯一转换入口，页面/服务不得各自写死）。
 *
 * 项目约定（2026-09 统一）：
 * - 库内 `transport_station` / `transport_route` 站点坐标、小程序 `wx.getLocation(type=gcj02)`、
 *   高德（AMAP）Web 服务均为 **GCJ-02**，是"附近公交 / 距离 / 路网 ETA"的统一计算坐标系；
 * - 车载 GPS / 北斗设备上报为 **WGS-84**，进算法 / 落库前必须先 {@link #wgs84ToGcj02}；
 * - 百度地图（BD-09）仅在对接百度服务时使用，进出都要转换。
 *
 * 标准公开实现，中国境外坐标不偏移、直接返回。
 */
public final class GeoCoordUtil {

    /** 长半轴 */
    private static final double A = 6378245.0;
    /** 偏心率平方 */
    private static final double EE = 0.00669342162296594323;
    /** BD-09 圆周率 */
    private static final double X_PI = Math.PI * 3000.0 / 180.0;

    private GeoCoordUtil() {
    }

    /** WGS-84 → GCJ-02，返回 [lon, lat]；中国境外不偏移直接返回 */
    public static double[] wgs84ToGcj02(double lon, double lat) {
        if (outOfChina(lon, lat)) {
            return new double[]{lon, lat};
        }
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lon + dLon, lat + dLat};
    }

    /**
     * GCJ-02 → WGS-84，返回 [lon, lat]。
     * 用一次加偏后的偏差反向迭代修正（两次迭代精度已优于 1e-6 度，约 0.1m 级）。
     */
    public static double[] gcj02ToWgs84(double lon, double lat) {
        if (outOfChina(lon, lat)) {
            return new double[]{lon, lat};
        }
        double wgsLon = lon;
        double wgsLat = lat;
        for (int i = 0; i < 2; i++) {
            double[] gcj = wgs84ToGcj02(wgsLon, wgsLat);
            wgsLon += lon - gcj[0];
            wgsLat += lat - gcj[1];
        }
        return new double[]{wgsLon, wgsLat};
    }

    /** GCJ-02 → BD-09，返回 [lon, lat] */
    public static double[] gcj02ToBd09(double lon, double lat) {
        double z = Math.sqrt(lon * lon + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lon) + 0.000003 * Math.cos(lon * X_PI);
        return new double[]{z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    /** BD-09 → GCJ-02，返回 [lon, lat] */
    public static double[] bd09ToGcj02(double lon, double lat) {
        double x = lon - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.cos(theta), z * Math.sin(theta)};
    }

    /** WGS-84 → BD-09，返回 [lon, lat] */
    public static double[] wgs84ToBd09(double lon, double lat) {
        double[] gcj = wgs84ToGcj02(lon, lat);
        return gcj02ToBd09(gcj[0], gcj[1]);
    }

    /** BD-09 → WGS-84，返回 [lon, lat] */
    public static double[] bd09ToWgs84(double lon, double lat) {
        double[] gcj = bd09ToGcj02(lon, lat);
        return gcj02ToWgs84(gcj[0], gcj[1]);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private static boolean outOfChina(double lon, double lat) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }
}
