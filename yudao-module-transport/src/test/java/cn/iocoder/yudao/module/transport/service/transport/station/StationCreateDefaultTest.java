package cn.iocoder.yudao.module.transport.service.transport.station;

import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.StationCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 新增站点默认权限单测（需求 §34）：
 * 新站点**不能自动**获得车辆权限或调度资格，也不自动加入线路；用户可达默认开（用户能走到）。
 */
@ExtendWith(MockitoExtension.class)
class StationCreateDefaultTest {

    @Mock private StationMapper stationMapper;

    private StationServiceImpl stationService;

    @BeforeEach
    void setUp() {
        stationService = new StationServiceImpl();
        ReflectionTestUtils.setField(stationService, "mapper", stationMapper);
    }

    @Test
    void new_station_defaults_to_no_vehicle_access_and_no_dispatch() {
        when(stationMapper.insert(any(StationDO.class))).thenAnswer(inv -> {
            StationDO d = inv.getArgument(0);
            d.setId(999L);
            return 1;
        });
        StationCreateReqVO reqVO = new StationCreateReqVO();
        reqVO.setStationCode("ST999");
        reqVO.setStationName("测试新站");
        reqVO.setStationLevel(2);
        reqVO.setLongitude(BigDecimal.valueOf(106.58));
        reqVO.setLatitude(BigDecimal.valueOf(29.53));
        reqVO.setStatus(0);
        // 故意不设置三个可达性字段 → 走默认

        Long id = stationService.create(reqVO);

        ArgumentCaptor<StationDO> captor = ArgumentCaptor.forClass(StationDO.class);
        verify(stationMapper).insert(captor.capture());
        StationDO saved = captor.getValue();
        assertEquals(999L, id);
        assertTrue(saved.getUserAccess(), "用户可达默认开（用户能走到站点）");
        assertFalse(saved.getVehicleAccess(), "车辆可达默认关：新增站点不能自动获得车辆权限");
        assertFalse(saved.getDispatchEnabled(), "调度资格默认关：新增站点不能自动成为场站/换乘站");
    }

    @Test
    void explicit_flags_are_respected() {
        when(stationMapper.insert(any(StationDO.class))).thenReturn(1);
        StationCreateReqVO reqVO = new StationCreateReqVO();
        reqVO.setStationCode("ST998");
        reqVO.setStationName("南门货运站");
        reqVO.setLongitude(BigDecimal.valueOf(106.579));
        reqVO.setLatitude(BigDecimal.valueOf(29.529));
        reqVO.setUserAccess(true);
        reqVO.setVehicleAccess(true);
        reqVO.setDispatchEnabled(true);

        stationService.create(reqVO);

        ArgumentCaptor<StationDO> captor = ArgumentCaptor.forClass(StationDO.class);
        verify(stationMapper).insert(captor.capture());
        assertTrue(captor.getValue().getVehicleAccess(), "管理员显式勾选后应保留");
        assertTrue(captor.getValue().getDispatchEnabled());
    }

}
