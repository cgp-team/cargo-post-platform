package cn.iocoder.yudao.module.transport.service.transport.send;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.RoutePreviewRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistancePairDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SEND_STATIONS_SAME;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_DISABLED;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 寄货路线预览单测（纯 Mockito）：站点校验（存在/启用/不相同）+ 高德路网距离/时间 + 降级/不可用。
 */
@ExtendWith(MockitoExtension.class)
class AppSendRouteInfoServiceTest {

    @Mock private StationMapper stationMapper;
    @Mock private AlgorithmClient algorithmClient;

    private AppSendRouteInfoService service;

    private final StationDO pickup = StationDO.builder()
            .id(1L).stationName("红花村站").status(0)
            .longitude(new BigDecimal("104.1234")).latitude(new BigDecimal("30.6012")).build();
    private final StationDO delivery = StationDO.builder()
            .id(2L).stationName("县城客运中心").status(0)
            .longitude(new BigDecimal("104.0657")).latitude(new BigDecimal("30.5723")).build();

    @BeforeEach
    void setUp() {
        service = new AppSendRouteInfoService();
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(service, "algorithmClient", algorithmClient);
    }

    private void stubBothStations() {
        when(stationMapper.selectById(1L)).thenReturn(pickup);
        when(stationMapper.selectById(2L)).thenReturn(delivery);
    }

    private void stubAmapRoute(double km, double seconds) {
        when(algorithmClient.distance(any())).thenReturn(AlgorithmDistanceRespDTO.builder()
                .distanceUnit("km")
                .pairs(List.of(AlgorithmDistancePairDTO.builder()
                        .distanceKm(km).durationSeconds(seconds).provider("amap").available(true).build()))
                .build());
    }

    @Test
    void routePreview_amap_success_returns_km_and_minutes() {
        stubBothStations();
        stubAmapRoute(18.62, 1920.0);
        RoutePreviewRespVO vo = service.routePreview(1L, 2L);
        assertTrue(vo.getAvailable());
        assertEquals("amap", vo.getProvider());
        assertEquals(0, new BigDecimal("18.62").compareTo(vo.getDistanceKm())); // km 直用
        assertEquals(32, vo.getDurationMinutes()); // ceil(1920/60)=32
        assertNull(vo.getWarning());
    }

    @Test
    void routePreview_same_station_blocked() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.routePreview(1L, 1L));
        assertEquals(SEND_STATIONS_SAME.getCode(), ex.getCode());
    }

    @Test
    void routePreview_missing_station_blocked() {
        when(stationMapper.selectById(1L)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.routePreview(1L, 2L));
        assertEquals(STATION_NOT_EXISTS.getCode(), ex.getCode());
    }

    @Test
    void routePreview_disabled_station_blocked() {
        when(stationMapper.selectById(1L)).thenReturn(pickup);
        when(stationMapper.selectById(2L)).thenReturn(StationDO.builder()
                .id(2L).stationName("县城客运中心").status(1)
                .longitude(new BigDecimal("104.0657")).latitude(new BigDecimal("30.5723")).build());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.routePreview(1L, 2L));
        assertEquals(STATION_DISABLED.getCode(), ex.getCode());
    }

    @Test
    void routePreview_euclidean_fallback_marks_provider_and_warning() {
        stubBothStations();
        when(algorithmClient.distance(any())).thenReturn(AlgorithmDistanceRespDTO.builder()
                .distanceUnit("km")
                .pairs(List.of(AlgorithmDistancePairDTO.builder()
                        .distanceKm(1.47).durationSeconds(212.0).provider("euclidean").available(true).build()))
                .build());
        RoutePreviewRespVO vo = service.routePreview(1L, 2L);
        assertTrue(vo.getAvailable());
        assertEquals("euclidean", vo.getProvider());
        assertEquals(4, vo.getDurationMinutes()); // ceil(212/60)=4
        assertTrue(vo.getWarning() != null && vo.getWarning().contains("直线估算"));
    }

    @Test
    void routePreview_algorithm_service_unavailable_returns_unavailable() {
        stubBothStations();
        when(algorithmClient.distance(any())).thenThrow(new RuntimeException("algorithm down"));
        RoutePreviewRespVO vo = service.routePreview(1L, 2L);
        assertEquals(Boolean.FALSE, vo.getAvailable());
        assertNull(vo.getDistanceKm());
    }

    @Test
    void routePreview_unreachable_returns_available_false() {
        stubBothStations();
        when(algorithmClient.distance(any())).thenReturn(AlgorithmDistanceRespDTO.builder()
                .distanceUnit("km")
                .pairs(List.of(AlgorithmDistancePairDTO.builder()
                        .provider("amap").available(false).build()))
                .build());
        RoutePreviewRespVO vo = service.routePreview(1L, 2L);
        assertEquals(Boolean.FALSE, vo.getAvailable());
        assertNull(vo.getDistanceKm());
        assertNull(vo.getDurationMinutes());
    }
}
