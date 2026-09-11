package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 车辆/司机时间冲突检测单测（需求 §48/§49/§52/§113）。
 *
 * 关键断言：同一车辆 10:00-11:00 与 10:30-11:30 必须判定冲突（区间重叠）。
 */
@ExtendWith(MockitoExtension.class)
class VehicleConflictTest {

    @Mock private TransportLegMapper legMapper;
    @Mock private TransportOrderMapper orderMapper;

    private LegConflictServiceImpl service;

    private static final LocalDateTime T10 = LocalDateTime.of(2026, 9, 11, 10, 0);
    private static final LocalDateTime T11 = LocalDateTime.of(2026, 9, 11, 11, 0);
    private static final LocalDateTime T1030 = LocalDateTime.of(2026, 9, 11, 10, 30);
    private static final LocalDateTime T1130 = LocalDateTime.of(2026, 9, 11, 11, 30);

    @BeforeEach
    void setUp() {
        service = new LegConflictServiceImpl();
        ReflectionTestUtils.setField(service, "legMapper", legMapper);
        ReflectionTestUtils.setField(service, "orderMapper", orderMapper);
    }

    private static TransportLegDO leg(long id, long orderId, Long vehicleId, Long driverId,
                                      LocalDateTime start, LocalDateTime end, Integer status) {
        return TransportLegDO.builder().id(id).orderId(orderId).legSequence(1)
                .vehicleId(vehicleId).driverId(driverId)
                .estimatedDeparture(start).estimatedArrival(end).status(status).build();
    }

    @Test
    void overlapping_windows_conflict() {
        TransportLegDO existing = leg(1, 100, 7L, 8L, T10, T11, TransportLegStatusEnum.IN_TRANSIT.getStatus());
        when(legMapper.selectList()).thenReturn(List.of(existing));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(100L).status(TransportOrderStatusEnum.ASSIGNED.getStatus()).build()));

        assertTrue(service.vehicleConflicts(7L, T1030, T1130, List.of(), null),
                "同一车辆 10:00-11:00 与 10:30-11:30 必须冲突");
        assertTrue(service.driverConflicts(8L, T1030, T1130, List.of(), null),
                "同一司机冲突同理");
    }

    @Test
    void same_time_window_fails_assert() {
        TransportLegDO existing = leg(1, 100, 7L, 8L, T10, T11, TransportLegStatusEnum.IN_TRANSIT.getStatus());
        when(legMapper.selectList()).thenReturn(List.of(existing));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(100L).status(TransportOrderStatusEnum.DEPARTED.getStatus()).build()));
        assertThrows(ServiceException.class,
                () -> service.assertNoConflict(7L, 8L, T1030, T1130, List.of(), null));
    }

    @Test
    void disjoint_window_is_ok() {
        TransportLegDO existing = leg(1, 100, 7L, 8L, T10, T11, TransportLegStatusEnum.IN_TRANSIT.getStatus());
        when(legMapper.selectList()).thenReturn(List.of(existing));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(100L).status(TransportOrderStatusEnum.ASSIGNED.getStatus()).build()));
        // 11:30 之后（含 15 分钟缓冲）不再冲突
        assertDoesNotThrow(() -> service.assertNoConflict(7L, 8L,
                LocalDateTime.of(2026, 9, 11, 11, 30), LocalDateTime.of(2026, 9, 11, 12, 30), List.of(), null));
    }

    @Test
    void completed_leg_and_finished_order_do_not_occupy() {
        TransportLegDO completed = leg(1, 100, 7L, 8L, T10, T11, TransportLegStatusEnum.COMPLETED.getStatus());
        when(legMapper.selectList()).thenReturn(List.of(completed));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(
                TransportOrderDO.builder().id(100L).status(TransportOrderStatusEnum.COMPLETED.getStatus()).build()));
        assertFalse(service.vehicleConflicts(7L, T10, T11, List.of(), null));
        assertFalse(service.driverConflicts(8L, T10, T11, List.of(), null));
    }

    @Test
    void in_planning_legs_are_considered() {
        // 不查库也发现本次在规划中的段冲突
        TransportLegDO planning = leg(0L, 200, 7L, 8L, T10, T11, TransportLegStatusEnum.ASSIGNED.getStatus());
        planning.setId(null);
        assertTrue(service.vehicleConflicts(7L, T1030, T1130, List.of(planning), null));
    }

    @Test
    void null_resources_never_conflict() {
        assertFalse(service.vehicleConflicts(null, T10, T11, List.of(), null));
        assertFalse(service.driverConflicts(null, T10, T11, List.of(), null));
        assertDoesNotThrow(() -> service.assertNoConflict(null, null, T10, T11, List.of(), null));
    }

    @Test
    void overlap_helper_boundaries() {
        assertTrue(LegConflictService.overlaps(T10, T11, T1030, T1130, 0));
        assertFalse(LegConflictService.overlaps(T10, T11, T11, T1130, 0), "首尾相接不算重叠");
        assertTrue(LegConflictService.overlaps(T10, T11, T11, T1130, 15), "缓冲 15 分钟时相接算冲突（需留作业时间）");
    }

}
