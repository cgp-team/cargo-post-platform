package cn.iocoder.yudao.module.transport.enums.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运输段状态机单测：合法流转放行，跳级/越级拒绝（需求 §115：非法操作必须返回业务错误）。
 */
class TransportLegStatusEnumTest {

    @Test
    void legal_forward_flow() {
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.PLANNED, TransportLegStatusEnum.ASSIGNED));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.ASSIGNED, TransportLegStatusEnum.DRIVER_ACCEPTED));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.DRIVER_ACCEPTED, TransportLegStatusEnum.NAVIGATING));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.NAVIGATING, TransportLegStatusEnum.ARRIVED_ORIGIN));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.ARRIVED_ORIGIN, TransportLegStatusEnum.LOADING));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.LOADING, TransportLegStatusEnum.IN_TRANSIT));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.IN_TRANSIT, TransportLegStatusEnum.ARRIVED_DESTINATION));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.ARRIVED_DESTINATION, TransportLegStatusEnum.HANDOVER));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.HANDOVER, TransportLegStatusEnum.COMPLETED));
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.DELIVERING, TransportLegStatusEnum.COMPLETED));
    }

    @Test
    void illegal_jump_rejected() {
        // 运输中 直接 已完成 → 必须拒绝（需求 §115）
        assertFalse(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.IN_TRANSIT, TransportLegStatusEnum.COMPLETED));
        // 已规划 直接 运输中 → 必须拒绝（必须先接单/到达/装货）
        assertFalse(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.PLANNED, TransportLegStatusEnum.IN_TRANSIT));
        // 已分配 直接 到达终点 → 拒绝
        assertFalse(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.ASSIGNED, TransportLegStatusEnum.ARRIVED_DESTINATION));
    }

    @Test
    void same_status_is_idempotent_and_completed_is_terminal() {
        assertTrue(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.IN_TRANSIT, TransportLegStatusEnum.IN_TRANSIT));
        assertFalse(TransportLegStatusEnum.canTransit(TransportLegStatusEnum.COMPLETED, TransportLegStatusEnum.IN_TRANSIT));
    }

}
