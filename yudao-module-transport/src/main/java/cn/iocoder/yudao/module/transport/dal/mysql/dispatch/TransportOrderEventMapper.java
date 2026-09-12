package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportOrderEventDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TransportOrderEventMapper extends BaseMapperX<TransportOrderEventDO> {

    /** 按订单查事件时间线（按事件时间升序） */
    default List<TransportOrderEventDO> selectListByOrderId(Long orderId) {
        return selectList(new LambdaQueryWrapperX<TransportOrderEventDO>()
                .eq(TransportOrderEventDO::getOrderId, orderId)
                .orderByAsc(TransportOrderEventDO::getEventTime)
                .orderByAsc(TransportOrderEventDO::getId));
    }

}
