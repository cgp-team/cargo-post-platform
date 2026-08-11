package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 运输订单状态（调度闭环状态机）。
 */
@Getter
@AllArgsConstructor
public enum TransportOrderStatusEnum {

    /** 已创建，待调度 */
    CREATED(0, "待调度"),
    /** 已归入调度订单池 */
    POOLED(1, "已入池"),
    /** 已分配到调度方案 */
    ASSIGNED(2, "已分配"),
    /** 车辆已发车 */
    DEPARTED(3, "已发车"),
    /** 已完成 */
    COMPLETED(4, "已完成"),
    /** 已取消 */
    CANCELLED(5, "已取消");

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
