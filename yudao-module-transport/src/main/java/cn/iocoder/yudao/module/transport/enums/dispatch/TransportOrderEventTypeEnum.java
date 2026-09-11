package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单事件类型（transport_order_event.event_type，订单时间线 + 事件驱动通知的源头）。
 */
@Getter
@AllArgsConstructor
public enum TransportOrderEventTypeEnum {

    ORDER_CREATED("ORDER_CREATED", "订单创建"),
    REVIEW_PASSED("REVIEW_PASSED", "审核通过"),
    REVIEW_REJECTED("REVIEW_REJECTED", "审核拒绝"),
    STATION_RECOMMENDED("STATION_RECOMMENDED", "推荐送站"),
    STATION_CONFIRMED("STATION_CONFIRMED", "送站确认"),
    POOLED("POOLED", "入池待调度"),
    DISPATCHED("DISPATCHED", "智能调度完成"),
    PLAN_ISSUED("PLAN_ISSUED", "方案下发"),
    DEPARTED("DEPARTED", "车辆发车"),
    LEG_DEPARTED("LEG_DEPARTED", "运输段发车"),
    LEG_ARRIVED("LEG_ARRIVED", "运输段到达"),
    HANDOVER_CREATED("HANDOVER_CREATED", "发起交接"),
    HANDOVER_CONFIRMED("HANDOVER_CONFIRMED", "交接确认"),
    HANDOVER_DISPUTED("HANDOVER_DISPUTED", "交接争议"),
    ARRIVED("ARRIVED", "到达目的站"),
    COMPLETED("COMPLETED", "订单完成"),
    CANCELLED("CANCELLED", "订单取消"),
    EXCEPTION("EXCEPTION", "运输异常"),
    REDISPATCHED("REDISPATCHED", "异常重调度"),
    PLAN_CREATED("PLAN_CREATED", "生成运输方案"),
    PLAN_REPLANNED("PLAN_REPLANNED", "重新规划运输段"),
    LEG_ASSIGNED("LEG_ASSIGNED", "运输段已分配司机"),
    LEG_ACCEPTED("LEG_ACCEPTED", "司机已接单"),
    LEG_STARTED("LEG_STARTED", "运输段已开始"),
    LEG_COMPLETED("LEG_COMPLETED", "运输段已完成"),
    DRIVER_ARRIVED("DRIVER_ARRIVED", "司机已到达站点"),
    HANDOVER_REQUIRED("HANDOVER_REQUIRED", "需要换乘交接"),
    HANDOVER_STARTED("HANDOVER_STARTED", "开始交接货物"),
    ORDER_ARRIVED("ORDER_ARRIVED", "货物到达目的站"),
    ORDER_PICKUP_REQUIRED("ORDER_PICKUP_REQUIRED", "等待用户取货"),
    ORDER_EXCEPTION("ORDER_EXCEPTION", "订单异常");

    private final String code;
    private final String name;

    public static String nameOf(String code) {
        if (code == null) {
            return "";
        }
        for (TransportOrderEventTypeEnum item : values()) {
            if (item.getCode().equals(code)) {
                return item.getName();
            }
        }
        return "";
    }

}
