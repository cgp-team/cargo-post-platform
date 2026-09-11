package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 异常重调度单测（需求 §77/§108）：只重规划受影响的那一段，已完成段不动；无可用资源时保持异常。
 */
@ExtendWith(MockitoExtension.class)
class MultiLegReplanTest {

    @Mock private TransportLegMapper legMapper;
    @Mock private DriverVehicleMapper driverVehicleMapper;
    @Mock private VehicleMapper vehicleMapper;
    @Mock private LegConflictService legConflictService;
    @Mock private OrderEventService orderEventService;
    @Mock private UserNotificationService userNotificationService;
    @Mock private DispatchPlanMapper dispatchPlanMapper;

    private MultiLegServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MultiLegServiceImpl();
        ReflectionTestUtils.setField(service, "legMapper", legMapper);
        ReflectionTestUtils.setField(service, "driverVehicleMapper", driverVehicleMapper);
        ReflectionTestUtils.setField(service, "vehicleMapper", vehicleMapper);
        ReflectionTestUtils.setField(service, "legConflictService", legConflictService);
        ReflectionTestUtils.setField(service, "orderEventService", orderEventService);
        ReflectionTestUtils.setField(service, "userNotificationService", userNotificationService);
        ReflectionTestUtils.setField(service, "dispatchPlanMapper", dispatchPlanMapper);
    }

    private static TransportLegDO brokenLeg() {
        return TransportLegDO.builder().id(11L).orderId(100L).planId(55L).legSequence(2)
                .vehicleId(1L).driverId(1L)
                .estimatedDeparture(LocalDateTime.of(2026, 9, 11, 10, 0))
                .estimatedArrival(LocalDateTime.of(2026, 9, 11, 11, 0))
                .status(TransportLegStatusEnum.EXCEPTION.getStatus()).build();
    }

    @Test
    void replan_switches_to_other_resource_without_touching_other_legs() {
        when(legMapper.selectById(11L)).thenReturn(brokenLeg());
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(1L).vehicleId(1L).status(1).build(),
                DriverVehicleDO.builder().driverId(2L).vehicleId(2L).status(1).build()));
        when(legConflictService.vehicleConflicts(any(), any(), any(), any(), any())).thenReturn(false);
        when(legConflictService.driverConflicts(any(), any(), any(), any(), any())).thenReturn(false);

        service.replanLeg(11L, "车辆故障");

        ArgumentCaptor<TransportLegDO> captor = ArgumentCaptor.forClass(TransportLegDO.class);
        verify(legMapper).updateById(captor.capture());
        assertEquals(2L, captor.getValue().getDriverId(), "应换到另一名司机");
        assertEquals(TransportLegStatusEnum.ASSIGNED.getStatus(), captor.getValue().getStatus(),
                "重调度后回到已分配");
        verify(orderEventService).record(eq(100L), any(), any());
        // 只改这一段：不应改动其他段（未发生任何删除/批量更新）
        verify(legMapper, never()).deleteById(any());
    }

    @Test
    void replan_fails_when_no_resource_and_marks_plan_exception() {
        when(legMapper.selectById(11L)).thenReturn(brokenLeg());
        when(driverVehicleMapper.selectActiveBindings()).thenReturn(List.of(
                DriverVehicleDO.builder().driverId(1L).vehicleId(1L).status(1).build()));
        lenient().when(legConflictService.vehicleConflicts(any(), any(), any(), any(), any())).thenReturn(false);
        lenient().when(legConflictService.driverConflicts(any(), any(), any(), any(), any())).thenReturn(false);

        assertThrows(ServiceException.class, () -> service.replanLeg(11L, "无车"));

        ArgumentCaptor<DispatchPlanDO> planCaptor = ArgumentCaptor.forClass(DispatchPlanDO.class);
        verify(dispatchPlanMapper).updateById(planCaptor.capture());
        assertEquals(DispatchPlanStatusEnum.EXCEPTION.getStatus(), planCaptor.getValue().getStatus(),
                "无可用资源时方案置异常等人工介入");
        verify(legMapper, never()).updateById(any(TransportLegDO.class));
    }

}
