package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo.NotificationPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TransportUserNotificationMapper extends BaseMapperX<TransportUserNotificationDO> {

    default PageResult<TransportUserNotificationDO> selectPage(NotificationPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eqIfPresent(TransportUserNotificationDO::getUserId, reqVO.getUserId())
                .eqIfPresent(TransportUserNotificationDO::getEventType, reqVO.getEventType())
                .eqIfPresent(TransportUserNotificationDO::getOrderId, reqVO.getOrderId())
                .eqIfPresent(TransportUserNotificationDO::getReadStatus, reqVO.getReadStatus())
                .betweenIfPresent(TransportUserNotificationDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(TransportUserNotificationDO::getId));
    }

    /** 小程序「消息中心」：本人通知分页 */
    default PageResult<TransportUserNotificationDO> selectPageByUserId(Long userId, Integer readStatus,
                                                                       PageParam pageParam) {
        return selectPage(pageParam, new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eq(TransportUserNotificationDO::getUserId, userId)
                .eqIfPresent(TransportUserNotificationDO::getReadStatus, readStatus)
                .orderByDesc(TransportUserNotificationDO::getId));
    }

    /** 本人未读通知数（消息中心红点） */
    default Long selectUnreadCount(Long userId) {
        return selectCount(new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eq(TransportUserNotificationDO::getUserId, userId)
                .eq(TransportUserNotificationDO::getReadStatus, 0));
    }

    /** 本人某订单的通知 */
    default List<TransportUserNotificationDO> selectListByUserAndOrder(Long userId, Long orderId) {
        return selectList(new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eq(TransportUserNotificationDO::getUserId, userId)
                .eqIfPresent(TransportUserNotificationDO::getOrderId, orderId)
                .orderByDesc(TransportUserNotificationDO::getId));
    }

    /** 幂等查询：同一事件（eventId + 接收方 + 事件类型）是否已推送 */
    default TransportUserNotificationDO selectByEvent(String eventId, Long recipientId, String eventType) {
        return selectOne(new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eq(TransportUserNotificationDO::getEventId, eventId)
                .eq(TransportUserNotificationDO::getUserId, recipientId)
                .eq(TransportUserNotificationDO::getEventType, eventType)
                .last("LIMIT 1"));
    }

    /** 司机消息分页（接收方=司机） */
    default PageResult<TransportUserNotificationDO> selectPageByDriverId(Long driverId, Integer readStatus,
                                                                        PageParam pageParam) {
        return selectPage(pageParam, new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eq(TransportUserNotificationDO::getUserId, driverId)
                .eq(TransportUserNotificationDO::getRecipientType, "DRIVER")
                .eqIfPresent(TransportUserNotificationDO::getReadStatus, readStatus)
                .orderByDesc(TransportUserNotificationDO::getId));
    }

    /** 司机未读数 */
    default Long selectUnreadCountByDriver(Long driverId) {
        return selectCount(new LambdaQueryWrapperX<TransportUserNotificationDO>()
                .eq(TransportUserNotificationDO::getUserId, driverId)
                .eq(TransportUserNotificationDO::getRecipientType, "DRIVER")
                .eq(TransportUserNotificationDO::getReadStatus, 0));
    }

}
