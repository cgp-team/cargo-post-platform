package cn.iocoder.yudao.module.transport.enums.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-21：订单状态机 14 状态流转矩阵单测。
 *
 * 核心回归场景：异常单（EXCEPTION）不允许被改回正常态（如 EXCEPTION → DELIVERING），
 * 终态（COMPLETED/CANCELLED）不允许再流转。
 */
class OrderStatusTransitionMatrixTest {

    @Test
    void normal_lifecycle_path_is_allowed() {
        // 主路径：创建 → 审核 → 待入池 → 入池 → 分配 → 发车 → 运输中 → 派送 → 完成
        assertTrue(TransportOrderStatusEnum.canTransit(0, 8));  // CREATED → READY_FOR_POOL
        assertTrue(TransportOrderStatusEnum.canTransit(8, 1));  // READY_FOR_POOL → POOLED
        assertTrue(TransportOrderStatusEnum.canTransit(1, 2));  // POOLED → ASSIGNED
        assertTrue(TransportOrderStatusEnum.canTransit(2, 3));  // ASSIGNED → DEPARTED
        assertTrue(TransportOrderStatusEnum.canTransit(3, 10)); // DEPARTED → IN_TRANSIT
        assertTrue(TransportOrderStatusEnum.canTransit(10, 12));// IN_TRANSIT → DELIVERING
        assertTrue(TransportOrderStatusEnum.canTransit(12, 4)); // DELIVERING → COMPLETED
    }

    @Test
    void multileg_path_is_allowed() {
        // 多段联运：运输中 → 换乘中 → 部分完成 → 运输中 → ... → 完成
        assertTrue(TransportOrderStatusEnum.canTransit(10, 11)); // IN_TRANSIT → TRANSFERRING
        assertTrue(TransportOrderStatusEnum.canTransit(11, 9));  // TRANSFERRING → PARTIALLY_COMPLETED
        assertTrue(TransportOrderStatusEnum.canTransit(9, 10));  // PARTIALLY_COMPLETED → IN_TRANSIT
        assertTrue(TransportOrderStatusEnum.canTransit(11, 10)); // TRANSFERRING → IN_TRANSIT（接货续运）
        assertTrue(TransportOrderStatusEnum.canTransit(10, 4));  // IN_TRANSIT → COMPLETED
    }

    @Test
    void review_branch_is_allowed() {
        assertTrue(TransportOrderStatusEnum.canTransit(0, 6)); // CREATED → PENDING_REVIEW
        assertTrue(TransportOrderStatusEnum.canTransit(6, 8)); // PENDING_REVIEW → READY_FOR_POOL
        assertTrue(TransportOrderStatusEnum.canTransit(0, 7)); // CREATED → WAITING_CUSTOMER_ACTION
        assertTrue(TransportOrderStatusEnum.canTransit(7, 8)); // WAITING_CUSTOMER_ACTION → READY_FOR_POOL
        assertTrue(TransportOrderStatusEnum.canTransit(6, 5)); // 审核拒绝 → CANCELLED
    }

    @Test
    void exception_recovery_is_allowed() {
        // 异常重调度：异常单可回池/重分配/恢复运输
        assertTrue(TransportOrderStatusEnum.canTransit(13, 1)); // EXCEPTION → POOLED
        assertTrue(TransportOrderStatusEnum.canTransit(13, 2)); // EXCEPTION → ASSIGNED
        assertTrue(TransportOrderStatusEnum.canTransit(13, 10)); // EXCEPTION → IN_TRANSIT
        assertTrue(TransportOrderStatusEnum.canTransit(13, 5)); // EXCEPTION → CANCELLED
    }

    @Test
    void exception_must_not_return_to_normal_delivery_states() {
        // 清单点名的缺陷场景：异常单被改回正常派送/发车/换乘
        assertFalse(TransportOrderStatusEnum.canTransit(13, 12)); // EXCEPTION → DELIVERING 必须拒绝
        assertFalse(TransportOrderStatusEnum.canTransit(13, 3));  // EXCEPTION → DEPARTED
        assertFalse(TransportOrderStatusEnum.canTransit(13, 11)); // EXCEPTION → TRANSFERRING
        assertFalse(TransportOrderStatusEnum.canTransit(13, 9));  // EXCEPTION → PARTIALLY_COMPLETED
        assertFalse(TransportOrderStatusEnum.canTransit(13, 8));  // EXCEPTION → READY_FOR_POOL
    }

    @Test
    void final_states_have_no_outgoing_transitions() {
        for (TransportOrderStatusEnum target : TransportOrderStatusEnum.values()) {
            // COMPLETED 只允许"流转"到它自己（幂等放行），其余一律拒绝
            if (target != TransportOrderStatusEnum.COMPLETED) {
                assertFalse(TransportOrderStatusEnum.canTransit(4, target.getStatus()),
                        "COMPLETED 不允许再流转到 " + target);
            }
            if (target != TransportOrderStatusEnum.CANCELLED) {
                assertFalse(TransportOrderStatusEnum.canTransit(5, target.getStatus()),
                        "CANCELLED 不允许再流转到 " + target);
            }
        }
    }

    @Test
    void exception_and_cancel_are_open_to_any_non_final_source() {
        List<Integer> nonFinal = List.of(0, 1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13);
        for (Integer from : nonFinal) {
            assertTrue(TransportOrderStatusEnum.canTransit(from, 13), from + " 应可转异常");
            assertTrue(TransportOrderStatusEnum.canTransit(from, 5), from + " 应可取消");
        }
    }

    @Test
    void null_and_unknown_rejected() {
        assertFalse(TransportOrderStatusEnum.canTransit(null, 4));
        assertFalse(TransportOrderStatusEnum.canTransit(10, null));
        assertFalse(TransportOrderStatusEnum.canTransit(99, 4));
        assertFalse(TransportOrderStatusEnum.canTransit(10, 99));
    }

    @Test
    void backward_jumps_rejected() {
        // 已发车/在途不允许退回入池/分配前的状态
        assertFalse(TransportOrderStatusEnum.canTransit(3, 1)); // DEPARTED → POOLED
        assertFalse(TransportOrderStatusEnum.canTransit(10, 1)); // IN_TRANSIT → POOLED
        assertFalse(TransportOrderStatusEnum.canTransit(12, 2)); // DELIVERING → ASSIGNED
        // 正常在途不允许直接跳创建/审核
        assertFalse(TransportOrderStatusEnum.canTransit(10, 0));
        assertFalse(TransportOrderStatusEnum.canTransit(10, 6));
    }

    @Test
    void all_fourteen_states_covered() {
        Set<Integer> statuses = Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13);
        for (Integer s : statuses) {
            assertFalse(TransportOrderStatusEnum.nameOf(s).isEmpty(), "状态 " + s + " 应在枚举中定义");
        }
    }

}
