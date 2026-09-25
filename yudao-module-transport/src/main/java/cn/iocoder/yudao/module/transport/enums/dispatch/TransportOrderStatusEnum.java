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

    // ==================== BE-21：订单状态机流转表 ====================
    // 规则：canTransit(current, target) 按「target 的合法来源集合」判定。
    // EXCEPTION / CANCELLED 作为目标来源开放（任何非终态都可转异常/取消）；
    // COMPLETED / CANCELLED 作为来源是终态，一律不再流转。

    /** 是否终态 */
    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED;
    }

    /** 当前状态是否允许流转到本状态（枚举版） */
    public boolean canTransitFrom(TransportOrderStatusEnum current) {
        if (current == null) {
            return false;
        }
        if (current.isFinal()) {
            return false; // 终态不再流转
        }
        if (current == this) {
            return true; // 同状态重复推进按幂等放行（调用方各自决定是否跳过）
        }
        switch (this) {
            case POOLED: // 入池：来自审核链或异常重调度回池
                return current == CREATED || current == READY_FOR_POOL || current == PENDING_REVIEW
                        || current == WAITING_CUSTOMER_ACTION || current == EXCEPTION;
            case READY_FOR_POOL: // 审核通过待入池
                return current == CREATED || current == PENDING_REVIEW || current == WAITING_CUSTOMER_ACTION;
            case PENDING_REVIEW:
                return current == CREATED;
            case WAITING_CUSTOMER_ACTION:
                return current == CREATED;
            case ASSIGNED: // 分配到方案：来自订单池或异常重调度
                return current == POOLED || current == EXCEPTION;
            case DEPARTED:
                return current == ASSIGNED;
            case IN_TRANSIT:
                return current == DEPARTED || current == ASSIGNED || current == TRANSFERRING
                        || current == PARTIALLY_COMPLETED || current == EXCEPTION;
            case TRANSFERRING: // 换乘中
                return current == IN_TRANSIT || current == PARTIALLY_COMPLETED || current == DEPARTED;
            case PARTIALLY_COMPLETED: // 首段已交付换乘站
                return current == IN_TRANSIT || current == TRANSFERRING || current == DEPARTED;
            case DELIVERING: // 派送中（清单场景：EXCEPTION → DELIVERING 必须被拒）
                return current == IN_TRANSIT || current == TRANSFERRING || current == PARTIALLY_COMPLETED
                        || current == DEPARTED || current == ASSIGNED;
            case COMPLETED:
                return current == DELIVERING || current == IN_TRANSIT || current == PARTIALLY_COMPLETED
                        || current == TRANSFERRING || current == DEPARTED;
            case EXCEPTION: // 异常：来源开放（任何非终态可转异常）
                return true;
            case CANCELLED: // 取消：来源开放（任何非终态可取消）
                return true;
            case CREATED: // 初始态由建单落库进入，不允许"流转回来"
                return false;
            default:
                return false;
        }
    }

    /** 当前状态码是否允许流转到目标状态码（Integer 版；null/未知一律拒绝） */
    public static boolean canTransit(Integer currentStatus, Integer targetStatus) {
        if (currentStatus == null || targetStatus == null) {
            return false;
        }
        TransportOrderStatusEnum target = of(targetStatus);
        TransportOrderStatusEnum current = of(currentStatus);
        return target != null && current != null && target.canTransitFrom(current);
    }

    private static TransportOrderStatusEnum of(Integer status) {
        for (TransportOrderStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item;
            }
        }
        return null;
    }

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
