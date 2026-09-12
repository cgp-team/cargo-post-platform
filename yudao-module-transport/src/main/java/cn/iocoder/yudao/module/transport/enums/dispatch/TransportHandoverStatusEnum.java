package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 货物交接状态机（多段联运换乘站，见需求 §9/§62）：
 *
 * WAITING        等待交接（交接记录已创建，前序司机在途）
 * SOURCE_ARRIVED 前序司机已到达换乘站（通知后序司机来接货）
 * TARGET_WAITING 接收司机已到换乘站待接
 * HANDOVER       交接中（双方在场，核验货物）
 * COMPLETED      交接完成（原子推进：Leg1=已完成、Leg2=运输中）
 * TIMEOUT        交接超时（前序超时未到 → 转人工，见需求 §108）
 * CANCELLED      已取消
 * EXCEPTION      异常（件数不符/破损等争议）
 *
 * 核心规则：前序司机未确认到达（SOURCE_ARRIVED）时，后序司机不能确认收到货。
 */
@Getter
@AllArgsConstructor
public enum TransportHandoverStatusEnum {

    WAITING(0, "等待交接"),
    SOURCE_ARRIVED(1, "前序已到达"),
    TARGET_WAITING(2, "接收方待接"),
    HANDOVER(3, "交接中"),
    COMPLETED(4, "交接完成"),
    TIMEOUT(5, "交接超时"),
    CANCELLED(6, "已取消"),
    EXCEPTION(7, "异常");

    private final Integer status;
    private final String name;

    public static String nameOf(Integer status) {
        if (status == null) {
            return "";
        }
        for (TransportHandoverStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item.getName();
            }
        }
        return "";
    }

}
