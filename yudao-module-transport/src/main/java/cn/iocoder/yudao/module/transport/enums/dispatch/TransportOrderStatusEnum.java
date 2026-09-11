package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 运输订单状态（OrderLifecycle，订单生命周期 + 调度闭环状态机）。
 *
 * 审核前置（Phase 2 承运审核）：订单创建后进入审核门禁——
 * 自动审核通过 → READY_FOR_POOL（待入池，唯一可被归集入池的状态）；
 * 需客户操作 → WAITING_CUSTOMER_ACTION；需人工审核 → PENDING_REVIEW；
 * 明确拒运（ReviewStatus=REJECTED）→ CANCELLED 终态。
 */
@Getter
@AllArgsConstructor
public enum TransportOrderStatusEnum {

    /** 已创建（自动审核随即流转，不驻留） */
    CREATED(0, "已创建"),
    /** 已归入调度订单池 */
    POOLED(1, "已入池"),
    /** 已分配到调度方案 */
    ASSIGNED(2, "已分配"),
    /** 车辆已发车 */
    DEPARTED(3, "已发车"),
    /** 已完成 */
    COMPLETED(4, "已完成"),
    /** 已取消 */
    CANCELLED(5, "已取消"),
    /** 审核中/待人工审核（MANUAL_REVIEW 结果驻留） */
    PENDING_REVIEW(6, "待审核"),
    /** 待客户操作（CONDITIONAL 结果，客户完成替代交接后 → READY_FOR_POOL） */
    WAITING_CUSTOMER_ACTION(7, "待客户操作"),
    /** 待入池（审核通过，唯一可归集入池的状态） */
    READY_FOR_POOL(8, "待入池"),
    /** 部分完成（多段联运：首段已交付换乘站，剩余段仍在途） */
    PARTIALLY_COMPLETED(9, "部分完成"),
    /** 运输中（已有段在途） */
    IN_TRANSIT(10, "运输中"),
    /** 换乘中（多段联运正在换乘站交接） */
    TRANSFERRING(11, "换乘中"),
    /** 派送中（最后一段已到达目的站，等待用户取货/派送） */
    DELIVERING(12, "派送中"),
    /** 异常（超时/故障/交接争议，需人工介入） */
    EXCEPTION(13, "异常");

    private final Integer status;
    private final String name;

    public static String nameOf(Integer status) {
        if (status == null) {
            return "";
        }
        for (TransportOrderStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item.getName();
            }
        }
        return "";
    }

}
