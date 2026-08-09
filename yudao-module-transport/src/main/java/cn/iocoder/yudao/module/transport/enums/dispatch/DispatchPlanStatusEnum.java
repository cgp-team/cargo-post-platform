package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 调度方案状态。
 */
@Getter
@AllArgsConstructor
public enum DispatchPlanStatusEnum {

    /** 待审核 */
    PENDING(0, "待审核"),
    /** 审核通过，已下发 */
    ISSUED(1, "已下发"),
    /** 车辆已发车，执行中 */
    RUNNING(2, "执行中"),
    /** 已完成 */
    COMPLETED(3, "已完成"),
    /** 已作废（审核驳回或人工废弃） */
    VOID(4, "已作废");

    private final Integer status;
    private final String name;

}
