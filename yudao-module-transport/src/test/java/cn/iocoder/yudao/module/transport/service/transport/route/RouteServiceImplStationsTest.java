package cn.iocoder.yudao.module.transport.service.transport.route;

import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.RouteStationSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-094：线路经停站点的"整线覆盖保存"必须区分**没带这个键**与**带了空数组**。
 *
 * 旧写法（`RouteServiceImpl.saveRouteStations`）是先 `routeStationMapper.delete(...)` 再判
 * `items == null || items.isEmpty()` 就 return ⇒ 一个只带 routeId 的请求会**清空这条线路的全部站序**、
 * 什么都不写回，还因为方法正常返回而报成功；唯一的防线在前端 `RouteForm.vue` 的 `if (!stations.length)`。
 * 后端自己的规矩是"二次校验、不信任前端"（`TransportOrderServiceImpl` 里就有这条注释）。
 *
 * 第一条用例在修之前是红的：未修代码里 delete 一定会被调用。
 */
@ExtendWith(MockitoExtension.class)
class RouteServiceImplStationsTest {

    @Mock private RouteMapper mapper;
    @Mock private RouteStationMapper routeStationMapper;
    @Mock private StationMapper stationMapper;

    private RouteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RouteServiceImpl();
        ReflectionTestUtils.setField(service, "mapper", mapper);
        ReflectionTestUtils.setField(service, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(service, "stationMapper", stationMapper);
    }

    private RouteStationSaveReqVO reqWithStations(List<RouteStationSaveReqVO.Item> items) {
        RouteStationSaveReqVO reqVO = new RouteStationSaveReqVO();
        reqVO.setRouteId(5L);
        reqVO.setStations(items);
        return reqVO;
    }

    @Test
    void saveRouteStations_withoutStationsField_keepsExistingSequence() {
        when(mapper.selectById(5L)).thenReturn(RouteDO.builder().id(5L).build());

        service.saveRouteStations(reqWithStations(null));

        verify(routeStationMapper, never()).delete(any());
        verify(routeStationMapper, never()).insert(any(RouteStationDO.class));
    }

    @Test
    void saveRouteStations_withExplicitEmptyList_clearsSequenceOnly() {
        when(mapper.selectById(5L)).thenReturn(RouteDO.builder().id(5L).build());

        service.saveRouteStations(reqWithStations(List.of()));

        verify(routeStationMapper).delete(any());
        verify(routeStationMapper, never()).insert(any(RouteStationDO.class));
    }

    @Test
    void saveRouteStations_withItems_deletesThenReinsertsInGivenOrder() {
        when(mapper.selectById(5L)).thenReturn(RouteDO.builder().id(5L).build());
        StationDO a = new StationDO();
        a.setId(21L);
        a.setLongitude(new BigDecimal("106.0"));
        a.setLatitude(new BigDecimal("29.0"));
        StationDO b = new StationDO();
        b.setId(22L);
        b.setLongitude(new BigDecimal("106.5"));
        b.setLatitude(new BigDecimal("29.4"));
        when(stationMapper.selectBatchIds(any())).thenReturn(List.of(a, b));

        RouteStationSaveReqVO.Item i1 = new RouteStationSaveReqVO.Item();
        i1.setStationId(21L);
        RouteStationSaveReqVO.Item i2 = new RouteStationSaveReqVO.Item();
        i2.setStationId(22L);

        service.saveRouteStations(reqWithStations(List.of(i1, i2)));

        verify(routeStationMapper).delete(any());
        ArgumentCaptor<RouteStationDO> captor = ArgumentCaptor.forClass(RouteStationDO.class);
        verify(routeStationMapper, times(2)).insert(captor.capture());
        assertEquals(List.of(21L, 22L),
                captor.getAllValues().stream().map(RouteStationDO::getStationId).toList(), "站序按数组顺序落库");
        assertEquals(List.of(1, 2),
                captor.getAllValues().stream().map(RouteStationDO::getSequenceNo).toList(), "sequenceNo 从 1 递增");
    }
}
