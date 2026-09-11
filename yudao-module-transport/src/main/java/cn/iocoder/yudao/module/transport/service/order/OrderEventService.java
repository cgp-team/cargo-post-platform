package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportOrderEventDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;

import java.util.List;

/**
 * 订单事件服务（事件时间线）：订单状态每次流转都写一条事件，供用户端/后台展示时间线，
 * 并作为事件驱动通知（{@link cn.iocoder.yudao.module.transport.service.notification.UserNotificationService}）的源头。
 */
public interface OrderEventService {

    /** 记录订单事件（操作人 = 系统） */
    void record(Long orderId, TransportOrderEventTypeEnum eventType, String detail);

    /** 记录订单事件（含扩展数据 JSON，操作人 = 系统） */
    void record(Long orderId, TransportOrderEventTypeEnum eventType, String detail, String extraData);

    /** 记录订单事件（显式操作人） */
    void record(Long orderId, TransportOrderEventTypeEnum eventType, String operator, String detail, String extraData);

    /** 按订单查事件时间线（按事件时间升序） */
    List<TransportOrderEventDO> getTimeline(Long orderId);

    /** 事件类型名（未知类型返回原编码） */
    String typeName(String eventType);

}
