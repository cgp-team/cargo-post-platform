package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportOrderEventDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportOrderEventMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderEventTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单事件服务实现：写 transport_order_event。事件写入失败不阻断主流程（只记日志），
 * 避免"时间线记不上"导致下单/调度/妥投整体失败。
 */
@Service
@Validated
@Slf4j
public class OrderEventServiceImpl implements OrderEventService {

    /** 无登录态（系统触发，如定时任务/算法回调）时的操作人标识 */
    private static final String OPERATOR_SYSTEM = "系统";

    @Resource
    private TransportOrderEventMapper orderEventMapper;

    @Override
    public void record(Long orderId, TransportOrderEventTypeEnum eventType, String detail) {
        record(orderId, eventType, currentOperator(), detail, null);
    }

    @Override
    public void record(Long orderId, TransportOrderEventTypeEnum eventType, String detail, String extraData) {
        record(orderId, eventType, currentOperator(), detail, extraData);
    }

    @Override
    public void record(Long orderId, TransportOrderEventTypeEnum eventType, String operator, String detail,
                       String extraData) {
        if (orderId == null || eventType == null) {
            return;
        }
        try {
            orderEventMapper.insert(TransportOrderEventDO.builder()
                    .orderId(orderId)
                    .eventType(eventType.getCode())
                    .eventTime(LocalDateTime.now())
                    .operator(operator)
                    .detail(detail)
                    .extraData(extraData)
                    .build());
        } catch (Exception ex) {
            log.warn("[order-event] 订单 {} 事件 {} 写入失败：{}", orderId, eventType.getCode(), ex.getMessage());
        }
    }

    @Override
    public List<TransportOrderEventDO> getTimeline(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        return orderEventMapper.selectListByOrderId(orderId);
    }

    @Override
    public String typeName(String eventType) {
        String name = TransportOrderEventTypeEnum.nameOf(eventType);
        return name.isEmpty() ? eventType : name;
    }

    /** 当前操作人：优先登录用户昵称，无登录态用「系统」 */
    private static String currentOperator() {
        String nickname = SecurityFrameworkUtils.getLoginUserNickname();
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId != null ? String.valueOf(userId) : OPERATOR_SYSTEM;
    }

}
