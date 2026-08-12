package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverArriveReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverDepartReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverLocationReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.AppDriverOrderActionReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftExecutionMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_NOT_FOUND;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_ORDER_STATUS_ILLEGAL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 司机端写操作闭环纯 Mockito 单测：发车、到站、确认装车、确认送达、位置上报。
 */
@ExtendWith(MockitoExtension.class)
class DriverAppServiceImplTest {

    @Mock private DriverMapper driverMapper;
    @Mock private DriverVehicleMapper driverVehicleMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private ShiftMapper shiftMapper;
    @Mock private RouteMapper routeMapper;
    @Mock private RouteStationMapper routeStationMapper;
    @Mock private StationMapper stationMapper;
    @Mock private TransportOrderMapper transportOrderMapper;
    @Mock private CargoOrderMapper cargoOrderMapper;
    @Mock private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Mock private DispatchPlanMapper dispatchPlanMapper;
    @Mock private ShiftExecutionMapper shiftExecutionMapper;
    @Mock private VehicleLocationMapper vehicleLocationMapper;

    private DriverAppServiceImpl driverAppService;

    @BeforeEach
    void setUp() {
        driverAppService = new DriverAppServiceImpl();
        ReflectionTestUtils.setField(driverAppService, "driverMapper", driverMapper);
        ReflectionTestUtils.setField(driverAppService, "driverVehicleMapper", driverVehicleMapper);
        ReflectionTestUtils.setField(driverAppService, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(driverAppService, "shiftMapper", shiftMapper);
        ReflectionTestUtils.setField(driverAppService, "routeMapper", routeMapper);
        ReflectionTestUtils.setField(driverAppService, "routeStationMapper", routeStationMapper);
        ReflectionTestUtils.setField(driverAppService, "stationMapper", stationMapper);
        ReflectionTestUtils.setField(driverAppService, "transportOrderMapper", transportOrderMapper);
        ReflectionTestUtils.setField(driverAppService, "cargoOrderMapper", cargoOrderMapper);
        ReflectionTestUtils.setField(driverAppService, "dispatchPlanItemMapper", dispatchPlanItemMapper);
        ReflectionTestUtils.setField(driverAppService, "dispatchPlanMapper", dispatchPlanMapper);
        ReflectionTestUtils.setField(driverAppService, "shiftExecutionMapper", shiftExecutionMapper);
        ReflectionTestUtils.setField(driverAppService, "vehicleLocationMapper", vehicleLocationMapper);
    }

    @Test
    void depart_creates_execution_and_departs_cargo_orders() {
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).name("张三").build());
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(1L).vehicleId(7L).status(1).build()));
        when(dispatchPlanItemMapper.selectListByDriverId(1L)).thenReturn(List.of(
                DispatchPlanItemDO.builder().planId(100L).driverId(1L).orderId(1000L).build()));

        AppDriverDepartReqVO reqVO = new AppDriverDepartReqVO();
        reqVO.setDriverId(1L);
        reqVO.setShiftId(10L);
        driverAppService.depart(reqVO);

        // 创建当天执行记录：在途、绑定车辆、当天日期、发车时间
        ArgumentCaptor<ShiftExecutionDO> execCaptor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).insert(execCaptor.capture());
        assertEquals(10L, execCaptor.getValue().getShiftId());
        assertEquals(1L, execCaptor.getValue().getDriverId());
        assertEquals(7L, execCaptor.getValue().getVehicleId());
        assertEquals(LocalDate.now(), execCaptor.getValue().getExecDate());
        assertNotNull(execCaptor.getValue().getDepartTime());
        assertEquals(0, execCaptor.getValue().getStatus());
        // 该司机名下已分配的货运订单推进为已发车
        ArgumentCaptor<TransportOrderDO> orderCaptor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(transportOrderMapper).update(orderCaptor.capture(), any());
        assertEquals(TransportOrderStatusEnum.DEPARTED.getStatus(), orderCaptor.getValue().getStatus());
    }

    @Test
    void depart_driver_not_exists_throws() {
        AppDriverDepartReqVO reqVO = new AppDriverDepartReqVO();
        reqVO.setDriverId(99L);
        reqVO.setShiftId(10L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.depart(reqVO));

        assertEquals(DRIVER_NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(shiftExecutionMapper, transportOrderMapper);
    }

    @Test
    void arrive_mid_station_updates_current_station() {
        ShiftExecutionDO execution = ShiftExecutionDO.builder()
                .id(50L).shiftId(10L).driverId(1L).vehicleId(7L)
                .execDate(LocalDate.now()).status(0).build();
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, 1L, LocalDate.now()))
                .thenReturn(execution);
        when(routeStationMapper.selectListByRouteIds(List.of(5L))).thenReturn(List.of(
                RouteStationDO.builder().routeId(5L).stationId(11L).sequenceNo(1).build(),
                RouteStationDO.builder().routeId(5L).stationId(22L).sequenceNo(2).build()));

        AppDriverArriveReqVO reqVO = new AppDriverArriveReqVO();
        reqVO.setDriverId(1L);
        reqVO.setShiftId(10L);
        reqVO.setStationId(11L);
        driverAppService.arrive(reqVO);

        // 中途站：仅更新当前站点，仍在途、无到达时间
        ArgumentCaptor<ShiftExecutionDO> captor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(captor.capture());
        assertEquals(11L, captor.getValue().getCurrentStationId());
        assertEquals(0, captor.getValue().getStatus());
        assertNull(captor.getValue().getArriveTime());
    }

    @Test
    void arrive_terminal_station_completes_execution() {
        ShiftExecutionDO execution = ShiftExecutionDO.builder()
                .id(50L).shiftId(10L).driverId(1L).vehicleId(7L)
                .execDate(LocalDate.now()).status(0).build();
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(shiftMapper.selectById(10L)).thenReturn(ShiftDO.builder().id(10L).routeId(5L).build());
        when(shiftExecutionMapper.selectByShiftAndDriverAndDate(10L, 1L, LocalDate.now()))
                .thenReturn(execution);
        when(routeStationMapper.selectListByRouteIds(List.of(5L))).thenReturn(List.of(
                RouteStationDO.builder().routeId(5L).stationId(11L).sequenceNo(1).build(),
                RouteStationDO.builder().routeId(5L).stationId(22L).sequenceNo(2).build()));

        AppDriverArriveReqVO reqVO = new AppDriverArriveReqVO();
        reqVO.setDriverId(1L);
        reqVO.setShiftId(10L);
        reqVO.setStationId(22L); // 最大 sequence_no 的终点站
        driverAppService.arrive(reqVO);

        // 终点站：写到达时间，执行记录置已完成
        ArgumentCaptor<ShiftExecutionDO> captor = ArgumentCaptor.forClass(ShiftExecutionDO.class);
        verify(shiftExecutionMapper).updateById(captor.capture());
        assertEquals(22L, captor.getValue().getCurrentStationId());
        assertEquals(1, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getArriveTime());
    }

    @Test
    void pickupConfirm_pooled_order_departs() {
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(1L);
        reqVO.setOrderId(1000L);
        driverAppService.pickupConfirm(reqVO);

        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(transportOrderMapper).updateById(captor.capture());
        assertEquals(TransportOrderStatusEnum.DEPARTED.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void pickupConfirm_completed_order_throws() {
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.COMPLETED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(1L);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.pickupConfirm(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).updateById(any(TransportOrderDO.class));
    }

    @Test
    void deliver_departed_order_completes() {
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(1L);
        reqVO.setOrderId(1000L);
        driverAppService.deliver(reqVO);

        ArgumentCaptor<TransportOrderDO> captor = ArgumentCaptor.forClass(TransportOrderDO.class);
        verify(transportOrderMapper).updateById(captor.capture());
        assertEquals(TransportOrderStatusEnum.COMPLETED.getStatus(), captor.getValue().getStatus());
    }

    @Test
    void deliver_pooled_order_throws() {
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(transportOrderMapper.selectById(1000L)).thenReturn(TransportOrderDO.builder()
                .id(1000L).orderType(2).status(TransportOrderStatusEnum.POOLED.getStatus()).build());

        AppDriverOrderActionReqVO reqVO = new AppDriverOrderActionReqVO();
        reqVO.setDriverId(1L);
        reqVO.setOrderId(1000L);
        ServiceException ex = assertThrows(ServiceException.class, () -> driverAppService.deliver(reqVO));

        assertEquals(DRIVER_ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(transportOrderMapper, never()).updateById(any(TransportOrderDO.class));
    }

    @Test
    void reportLocation_upserts_by_vehicle() {
        when(driverMapper.selectById(1L)).thenReturn(DriverDO.builder().id(1L).build());
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(1L).vehicleId(7L).status(1).build()));
        // 首次无位置记录，再次调用命中已有记录
        VehicleLocationDO existing = VehicleLocationDO.builder().id(60L).vehicleId(7L).build();
        when(vehicleLocationMapper.selectByVehicleId(7L)).thenReturn(null, existing);

        AppDriverLocationReqVO reqVO = new AppDriverLocationReqVO();
        reqVO.setDriverId(1L);
        reqVO.setShiftId(10L);
        reqVO.setLongitude(new BigDecimal("120.1234567"));
        reqVO.setLatitude(new BigDecimal("30.1234567"));
        reqVO.setSpeedKmh(new BigDecimal("42.5"));
        driverAppService.reportLocation(reqVO);
        driverAppService.reportLocation(reqVO);

        // 每车一行：首次插入，再次更新，幂等
        ArgumentCaptor<VehicleLocationDO> insertCaptor = ArgumentCaptor.forClass(VehicleLocationDO.class);
        verify(vehicleLocationMapper, times(1)).insert(insertCaptor.capture());
        assertEquals(7L, insertCaptor.getValue().getVehicleId());
        assertEquals(10L, insertCaptor.getValue().getShiftId());
        assertEquals(0, insertCaptor.getValue().getLongitude().compareTo(new BigDecimal("120.1234567")));
        assertNotNull(insertCaptor.getValue().getReportTime());
        ArgumentCaptor<VehicleLocationDO> updateCaptor = ArgumentCaptor.forClass(VehicleLocationDO.class);
        verify(vehicleLocationMapper, times(1)).updateById(updateCaptor.capture());
        assertEquals(60L, updateCaptor.getValue().getId());
        assertNotNull(updateCaptor.getValue().getReportTime());
    }

}
