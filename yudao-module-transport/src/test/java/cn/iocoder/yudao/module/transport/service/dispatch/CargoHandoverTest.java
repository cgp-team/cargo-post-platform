package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportHandoverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportHandoverStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 货物交接顺序单测（需求 §9/§62/§114）：
 * 顺序必须是 A 到达(SOURCE_ARRIVED) → B 到场(TARGET_WAITING/HANDOVER) → B 确认接货(COMPLETED)，
 * **前序未确认到达时后序不能确认收到货**；完成后原子推进 Leg1=已完成、Leg2=运输中。
 */
@ExtendWith(MockitoExtension.class)
class CargoHandoverTest {

    @Mock private TransportHandoverMapper handoverMapper;
    @Mock private TransportLegMapper legMapper;
    @Mock private TransportOrderMapper orderMapper;
    @Mock private OrderEventService orderEventService;
    @Mock private UserNotificationService userNotificationService;
    @Mock private MultiLegService multiLegService;

    private HandoverServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HandoverServiceImpl();
        ReflectionTestUtils.setField(service, "handoverMapper", handoverMapper);
        ReflectionTestUtils.setField(service, "legMapper", legMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "orderEventService", orderEventService);
        ReflectionTestUtils.setField(service, "userNotificationService", userNotificationService);
        ReflectionTestUtils.setField(service, "multiLegService", multiLegService);
    }

    private void stubHandover(Integer status) {
        when(handoverMapper.selectById(9L)).thenReturn(TransportHandoverDO.builder()
                .id(9L).orderId(100L).planId(55L).legFromId(1L).legToId(2L).stationId(202L)
                .fromDriverId(1L).toDriverId(2L).status(status).build());
    }

    @Test
    void cannot_confirm_before_source_arrived() {
        stubHandover(TransportHandoverStatusEnum.WAITING.getStatus());
        assertThrows(ServiceException.class,
                () -> service.confirmHandover(9L, 2L, "photo.jpg"),
                "前序未到达时接收方不能确认接货");
        verify(handoverMapper, never()).updateById(any(TransportHandoverDO.class));
    }

    @Test
    void source_arrived_notifies_next_driver_and_marks_leg_arrived() {
        when(legMapper.selectById(1L)).thenReturn(TransportLegDO.builder()
                .id(1L).orderId(100L).planId(55L).legSequence(1).toStationId(202L)
                .driverId(1L).vehicleId(11L).status(TransportLegStatusEnum.IN_TRANSIT.getStatus()).build());
        when(handoverMapper.selectByLegFrom(1L)).thenReturn(TransportHandoverDO.builder()
                .id(9L).orderId(100L).legFromId(1L).legToId(2L).fromDriverId(1L).toDriverId(2L)
                .status(TransportHandoverStatusEnum.WAITING.getStatus()).build());

        service.markSourceArrived(100L, 1L);

        verify(handoverMapper).updateById(argThat((TransportHandoverDO h) ->
                TransportHandoverStatusEnum.SOURCE_ARRIVED.getStatus().equals(h.getStatus())));
        verify(multiLegService).forceLegStatus(eq(1L), eq(TransportLegStatusEnum.ARRIVED_DESTINATION), anyString());
        verify(userNotificationService).sendToDriver(eq(2L), any(), any(), eq(true), anyString(), anyString(),
                eq(100L), eq(55L), eq(2L));
    }

    @Test
    void confirm_advances_leg1_completed_and_leg2_in_transit_atomically() {
        stubHandover(TransportHandoverStatusEnum.SOURCE_ARRIVED.getStatus());
        when(legMapper.selectById(2L)).thenReturn(TransportLegDO.builder()
                .id(2L).orderId(100L).planId(55L).legSequence(2).driverId(2L).vehicleId(22L)
                .status(TransportLegStatusEnum.ASSIGNED.getStatus()).build());
        when(legMapper.selectListByOrderId(100L)).thenReturn(List.of(
                TransportLegDO.builder().id(1L).orderId(100L).legSequence(1).build(),
                TransportLegDO.builder().id(2L).orderId(100L).legSequence(2).build()));

        service.confirmHandover(9L, 2L, "photo.jpg");

        verify(handoverMapper).updateById(argThat((TransportHandoverDO h) ->
                TransportHandoverStatusEnum.COMPLETED.getStatus().equals(h.getStatus())
                        && h.getHandoverCompletedAt() != null && h.getConfirmedBy() == 2L));
        verify(multiLegService).forceLegStatus(eq(1L), eq(TransportLegStatusEnum.COMPLETED), anyString());
        verify(multiLegService).forceLegStatus(eq(2L), eq(TransportLegStatusEnum.IN_TRANSIT), anyString());
        // 最后一段接货 → 订单进入运输中（不得直接置完成）
        verify(orderMapper).update(argThat((TransportOrderDO o) ->
                TransportOrderStatusEnum.IN_TRANSIT.getStatus().equals(o.getStatus())), any());
    }

    @Test
    void confirm_is_idempotent_when_already_completed() {
        stubHandover(TransportHandoverStatusEnum.COMPLETED.getStatus());
        service.confirmHandover(9L, 2L, null);
        verify(handoverMapper, never()).updateById(any(TransportHandoverDO.class));
        verifyNoInteractions(multiLegService);
    }

    @Test
    void dispute_marks_exception_and_warns() {
        stubHandover(TransportHandoverStatusEnum.SOURCE_ARRIVED.getStatus());
        service.disputeHandover(9L, "件数不符");
        verify(handoverMapper).updateById(argThat((TransportHandoverDO h) ->
                TransportHandoverStatusEnum.EXCEPTION.getStatus().equals(h.getStatus())));
        verify(orderMapper).update(any(TransportOrderDO.class), any());
        verify(userNotificationService).sendToAdmin(any(), any(), anyString(), anyString(), eq(100L), eq(9L));
    }

}
