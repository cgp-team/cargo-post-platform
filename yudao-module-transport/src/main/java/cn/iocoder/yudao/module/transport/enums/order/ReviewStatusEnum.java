package cn.iocoder.yudao.module.transport.enums.order;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 承运审核结果（ReviewStatus），与订单生命周期（TransportOrderStatusEnum）分离。
 *
 * 对应 transport_cargo_order.review_status：
 * - PENDING 订单尚未完成审核（如创建后审核运行前 / 人工审核进行中）；
 * - PASSED 审核通过，可直接进入 READY_FOR_POOL 入池；
 * - CONDITIONAL 需客户完成操作（如送到指定站点），客户完成后 → READY_FOR_POOL；
 * - MANUAL_REVIEW 需后台人工判断，不能入池；
 * - REJECTED 明确不可运输，不能入池（订单生命周期置 CANCELLED）。
 */
@Getter
@AllArgsConstructor
public enum ReviewStatusEnum {

    PENDING(0, "待审核"),
    PASSED(1, "审核通过"),
    CONDITIONAL(2, "需客户操作"),
    MANUAL_REVIEW(3, "需人工审核"),
    REJECTED(4, "审核不通过");

    private final Integer status;
    private final String name;

    public static String nameOf(Integer status) {
        if (status == null) {
            return "";
        }
        for (ReviewStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item.getName();
            }
        }
        return "";
    }

}
