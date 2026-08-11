package cn.iocoder.yudao.module.transport.enums.transport;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 农产品商城订单状态。
 */
@Getter
@AllArgsConstructor
public enum ProductOrderStatusEnum {

    /** 待发货 */
    PENDING_DELIVERY(0, "待发货"),
    /** 已发货 */
    DELIVERED(1, "已发货"),
    /** 已完成 */
    COMPLETED(2, "已完成"),
    /** 已取消 */
    CANCELLED(3, "已取消");

    private final Integer status;
    private final String name;

    public static String nameOf(Integer status) {
        if (status == null) {
            return "";
        }
        for (ProductOrderStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item.getName();
            }
        }
        return "";
    }

}
