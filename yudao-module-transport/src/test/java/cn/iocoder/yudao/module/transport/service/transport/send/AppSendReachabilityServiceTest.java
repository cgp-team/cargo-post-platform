package cn.iocoder.yudao.module.transport.service.transport.send;

import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendReachabilityRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ServiceModeEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 当前位置可达性评估单测（核心业务：位置 ≠ 车辆能到的地方）。
 *
 * 规则：候选=启用且有坐标的站点 → 按距离升序（同距取 id）→ 道路距离优先、直线兜底 →
 * ≤0.3km 可就近服务（DOOR_PICKUP）；否则 USER_LOCATION_UNREACHABLE + NEAREST_STATION 推荐送站。
 */
@ExtendWith(MockitoExtension.class)
class AppSendReachabilityServiceTest {

    @Mock private StationMapper stationMapper;
    @Mock private AlgorithmClient algorithmClient;

    private AppSendReachabilityService service;

    @BeforeEach
    void setUp() {
        service = new AppSendReachabilityService();
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "algorithmClient", algorithmClient);
    }

    private StationDO station(long id, String name, int level, double lng, double lat) {
        return StationDO.builder().id(id).stationName(name).stationLevel(level).status(0)
                .longitude(BigDecimal.valueOf(lng)).latitude(BigDecimal.valueOf(lat)).build();
    }

    @Test
    void unreachable_whenNearestStationTooFar_recommendsNearestAndWalkMinutes() {
        // 用户站在重庆邮电大学校园内（106.5700, 29.5300）；最近站点约 0.65km → 不可达，需送站
        when(stationMapper.selectList()).thenReturn(List.of(
                station(101L, "重庆邮电大学站", 2, 106.5765, 29.5325),
                station(102L, "黄桷垭站", 1, 106.5748, 29.5370)));
        // 高德不可用 → 直线兜底（provider=HAVERSINE，如实标注"路线估算"）
        when(algorithmClient.distance(any())).thenThrow(new RuntimeException("algorithm down"));

        AppSendReachabilityRespVO resp = service.evaluate(29.5300, 106.5700);

        assertFalse(resp.getReachable());
        assertEquals(ReviewReasonCodeEnum.USER_LOCATION_UNREACHABLE.getCode(), resp.getReasonCode());
        assertEquals(ServiceModeEnum.NEAREST_STATION.getCode(), resp.getServiceMode());
        assertEquals("HAVERSINE", resp.getDistanceProvider());
        assertNotNull(resp.getRecommendedStation());
        assertEquals(101L, resp.getRecommendedStation().getId());
        assertTrue(resp.getDistanceKm().doubleValue() > 0.3);
        assertTrue(resp.getWalkMinutes() >= 1);
    }

    @Test
    void reachable_whenStationWithinThreshold() {
        // 用户就在站点旁（约 0.06km）→ 可安排就近取货（DOOR_PICKUP），无 reasonCode
        when(stationMapper.selectList()).thenReturn(List.of(
                station(101L, "重庆邮电大学站", 2, 106.5765, 29.5325)));
        when(algorithmClient.distance(any())).thenThrow(new RuntimeException("algorithm down"));

        AppSendReachabilityRespVO resp = service.evaluate(29.5320, 106.5760);

        assertTrue(resp.getReachable());
        assertNull(resp.getReasonCode());
        assertEquals(ServiceModeEnum.DOOR_PICKUP.getCode(), resp.getServiceMode());
        assertEquals(101L, resp.getRecommendedStation().getId());
    }

    @Test
    void noStation_returnsNoSafeHandoffPoint() {
        when(stationMapper.selectList()).thenReturn(List.of());

        AppSendReachabilityRespVO resp = service.evaluate(29.53, 106.57);

        assertFalse(resp.getReachable());
        assertEquals(ReviewReasonCodeEnum.NO_SAFE_HANDOFF_POINT.getCode(), resp.getReasonCode());
        assertNull(resp.getRecommendedStation());
    }

    @Test
    void missingCoordinates_returnsUnreachableWithoutQueryingStations() {
        AppSendReachabilityRespVO resp = service.evaluate(null, null);
        assertFalse(resp.getReachable());
        assertEquals(ReviewReasonCodeEnum.ROAD_UNREACHABLE.getCode(), resp.getReasonCode());
    }

    @Test
    void walkMinutes_usesWalkingSpeedAndRoundsUp() {
        assertEquals(1, AppSendReachabilityService.walkMinutes(0.05));  // 53m → 1 分钟
        assertEquals(6, AppSendReachabilityService.walkMinutes(0.43));  // 430m → 6 分钟
        assertEquals(7, AppSendReachabilityService.walkMinutes(0.5));   // 500m → 7 分钟
    }
}
