package cn.iocoder.yudao.module.transport.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 坐标系转换单测：项目统一以 GCJ-02 计算附近距离（站点表 / wx.getLocation(gcj02) / 高德一致），
 * WGS-84（车载 GPS、北斗）与 BD-09（百度）进出都必须经过本工具转换，页面/服务不得自行换算。
 */
class GeoCoordUtilTest {

    /** 天安门附近（GCJ-02），单位：度 */
    private static final double LNG = 116.397428;
    private static final double LAT = 39.90923;

    @Test
    void wgs84ToGcj02_offsetsInsideChinaAndKeepsOutside() {
        double[] gcj = GeoCoordUtil.wgs84ToGcj02(104.0657, 30.5723); // 成都附近
        // 加偏量级：百米级（经纬度偏差 0.001~0.01 度，方向随位置不同，不做符号假设）
        assertTrue(Math.abs(gcj[0] - 104.0657) > 0.0005 && Math.abs(gcj[0] - 104.0657) < 0.02);
        assertTrue(Math.abs(gcj[1] - 30.5723) > 0.0002 && Math.abs(gcj[1] - 30.5723) < 0.02);
        // 偏移不可忽略：直接用 WGS-84 坐标与 GCJ-02 站点比较会产生百米级误差（本次统一坐标系的原因）
        assertTrue(Math.abs(gcj[0] - 104.0657) + Math.abs(gcj[1] - 30.5723) > 0.001);
        // 境外不偏移
        double[] abroad = GeoCoordUtil.wgs84ToGcj02(-0.1278, 51.5074);
        assertEquals(-0.1278, abroad[0]);
        assertEquals(51.5074, abroad[1]);
    }

    @Test
    void gcj02ToWgs84_isInverseOfWgs84ToGcj02() {
        double[] gcj = GeoCoordUtil.wgs84ToGcj02(LNG, LAT);
        double[] wgs = GeoCoordUtil.gcj02ToWgs84(gcj[0], gcj[1]);
        assertEquals(LNG, wgs[0], 1e-5);
        assertEquals(LAT, wgs[1], 1e-5);
    }

    @Test
    void bd09RoundTrip_staysWithinMeterLevel() {
        double[] bd = GeoCoordUtil.gcj02ToBd09(LNG, LAT);
        double[] gcj = GeoCoordUtil.bd09ToGcj02(bd[0], bd[1]);
        assertEquals(LNG, gcj[0], 1e-6);
        assertEquals(LAT, gcj[1], 1e-6);
        // WGS-84 ↔ BD-09 组合换算也要可逆
        double[] bd2 = GeoCoordUtil.wgs84ToBd09(LNG, LAT);
        double[] wgs = GeoCoordUtil.bd09ToWgs84(bd2[0], bd2[1]);
        assertEquals(LNG, wgs[0], 1e-5);
        assertEquals(LAT, wgs[1], 1e-5);
    }
}
