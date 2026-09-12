package cn.iocoder.yudao.module.transport.service.transport.transit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 现实公交数据源解析单测（不联网）：高德周边搜索响应 → 附近站点。
 * 覆盖：正常解析、status 非 1、pois 缺失、单条缺坐标跳过、distance 米→公里。
 */
class AmapTransitProviderTest {

    @Test
    void parseStations_parsesPoisWithDistanceAndCoordinates() {
        String json = "{\"status\":\"1\",\"count\":\"2\",\"pois\":["
                + "{\"name\":\"人民公园站\",\"location\":\"104.123456,30.654321\",\"distance\":\"320\",\"address\":\"人民路1号\"},"
                + "{\"name\":\"火车北站\",\"location\":\"104.200000,30.700000\",\"distance\":\"1500\",\"address\":\"[]\"}]}";

        List<TransitProvider.TransitStation> stations = AmapTransitProvider.parseStations(json);

        assertEquals(2, stations.size());
        TransitProvider.TransitStation first = stations.get(0);
        assertEquals("人民公园站", first.name());
        assertEquals(104.123456, first.longitude(), 1e-9);
        assertEquals(30.654321, first.latitude(), 1e-9);
        assertEquals(0.32, first.distanceKm(), 1e-9); // 320 米 → 0.32 公里
        assertEquals("人民路1号", first.address());
        // 数据来源必须标注 REAL_TRANSIT，绝不冒充项目线路
        assertEquals(TransitProvider.REAL_TRANSIT, first.dataSource());
        assertNull(stations.get(1).address()); // "[]" 视为无地址
        // 公交站 POI 的 address 是途经线路（真实高德形态）：拆成线路名供展示，地址片段要过滤
        assertEquals(java.util.List.of("125路"), AmapTransitProvider.parseLines("125路"));
        assertEquals(java.util.List.of("184路", "801路", "K13线"), AmapTransitProvider.parseLines("184路;801路;K13线"));
        assertTrue(AmapTransitProvider.parseLines("人民路1号").isEmpty());
        assertTrue(AmapTransitProvider.parseLines("锦悦西路站").isEmpty());
        assertTrue(AmapTransitProvider.parseLines(null).isEmpty());
    }

    @Test
    void parseStations_ignoresFailedOrMalformedResponses() {
        assertTrue(AmapTransitProvider.parseStations(null).isEmpty());
        assertTrue(AmapTransitProvider.parseStations("").isEmpty());
        assertTrue(AmapTransitProvider.parseStations("not-json").isEmpty());
        assertTrue(AmapTransitProvider.parseStations("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}").isEmpty());
        // 单条缺坐标 / 坐标非法 → 跳过该条，不影响其他条
        List<TransitProvider.TransitStation> stations = AmapTransitProvider.parseStations(
                "{\"status\":\"1\",\"pois\":[{\"name\":\"坏站点\"},{\"name\":\"好站点\",\"location\":\"104.1,30.1\"}]}");
        assertEquals(1, stations.size());
        assertEquals("好站点", stations.get(0).name());
    }
}
