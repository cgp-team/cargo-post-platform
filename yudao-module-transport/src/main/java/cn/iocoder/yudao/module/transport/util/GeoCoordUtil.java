package cn.iocoder.yudao.module.transport.util;

/**
 * 坐标系转换工具：WGS-84 → GCJ-02（火星坐标系）。
 *
 * 适配层责任第 2 条：调用算法前完成坐标转换。当前站点按 GCJ-02 直接录入、
 * 司机端微信 getLocation 上报同为 GCJ-02，故现状安全（无需转换）；
 * 接入车载 GPS / 北斗（WGS-84）前，设备上报的车辆坐标需先经 {@link #wgs84ToGcj02}
 * 转换再进算法 / 落库，否则距离计算偏移百米级。
 *
 * 标准 GCJ-02 加偏算法（公开实现），中国境外坐标不偏移直接返回。
 */
public final class GeoCoordUtil {

    /** 长半轴 */
    private static final double A = 6378245.0;
    /** 偏心率平方 */
    private static final double EE = 0.00669342162296594323;

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
