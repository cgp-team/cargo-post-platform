package cn.iocoder.yudao.module.transport.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GeoDistanceUtil 单测：Haversine 距离 + 到站分钟估算 + 坐标缺失返回 null。
 * 覆盖"车来取货/送货提醒"与"调度方案总里程换算"共用的纯计算逻辑。
 */
class GeoDistanceUtilTest {

    @Test
    void haversineKm_known_latitude_degree() {
        // 0.1° 纬度差 ≈ 111.19 km/° × 0.1 ≈ 11.12 km
        double d = GeoDistanceUtil.haversineKm(104.0, 30.0, 104.0, 30.1);
        assertEquals(11.12, d, 0.05);
    }

    @Test
    void haversineKm_same_point_is_zero() {
        assertEquals(0.0, GeoDistanceUtil.haversineKm(104.0, 30.0, 104.0, 30.0), 1e-9);
    }

    @Test
    void estimateMinutes_normal_case() {
        // 10 km @ 25 km/h = 24 分钟
        assertEquals(24, GeoDistanceUtil.estimateMinutes(10, 25));
    }

    @Test
    void estimateMinutes_short_distance_min_one() {
        // 0.5 km @ 25 = 1.2 分钟 → 取整 1 分钟（至少 1）
        assertEquals(1, GeoDistanceUtil.estimateMinutes(0.5, 25));
        // 距离为 0（车辆已在站点）→ 至少 1 分钟
        assertEquals(1, GeoDistanceUtil.estimateMinutes(0, 25));
    }

    @Test
    void estimateMinutes_invalid_speed_returns_one() {
        assertEquals(1, GeoDistanceUtil.estimateMinutes(10, 0));
    }

    @Test
    void computeKmAndMinutes_valid_inputs() {
        GeoDistanceUtil.DistanceEta result = GeoDistanceUtil.computeKmAndMinutes(
                104.0, 30.0, 104.0, 30.1, GeoDistanceUtil.DEFAULT_AVG_SPEED_KMH);
        assertNotNull(result);
        assertEquals(11.12, result.distKm(), 0.05);
        // 11.12 km @ 25 km/h = 26.7 → 27 分钟
        assertEquals(27, result.etaMinutes());
    }

    @Test
    void computeKmAndMinutes_null_coordinate_returns_null() {
        // 车辆未发车 / 站点坐标缺失时任一坐标为 null → 返回 null（前端不显示提醒）
        assertNull(GeoDistanceUtil.computeKmAndMinutes(null, 30.0, 104.0, 30.1,
                GeoDistanceUtil.DEFAULT_AVG_SPEED_KMH));
        assertNull(GeoDistanceUtil.computeKmAndMinutes(104.0, 30.0, null, 30.1,
                GeoDistanceUtil.DEFAULT_AVG_SPEED_KMH));
    }

    @Test
    void deviation_from_polyline() {
        // 沿纬度线的 polyline（经度 104.000 → 104.010 @ 纬度 30.000）
        List<double[]> poly = List.of(new double[]{104.000, 30.000}, new double[]{104.010, 30.000});
        // 点在线段上 → ~0
        assertEquals(0.0, GeoDistanceUtil.deviationMetersFromPolyline(104.005, 30.000, poly), 0.5);
        // 点偏北 0.0018°（纬度方向 ≈ 200m）→ 约 200m（>100m 阈值 → 偏航）
        double d = GeoDistanceUtil.deviationMetersFromPolyline(104.005, 30.0018, poly);
        assertTrue(d > 150 && d < 250, "偏北应约 200m，实际 " + d);
        // 线段端点外（前方 0.01° 经度 ≈ 963m）
        assertTrue(GeoDistanceUtil.deviationMetersFromPolyline(104.02, 30.000, poly) > 900);
        // 空/单点无法判定 → -1
        assertEquals(-1, GeoDistanceUtil.deviationMetersFromPolyline(104.0, 30.0, List.of(new double[]{104.0, 30.0})));
        assertEquals(-1, GeoDistanceUtil.deviationMetersFromPolyline(104.0, 30.0, null));
    }
}
