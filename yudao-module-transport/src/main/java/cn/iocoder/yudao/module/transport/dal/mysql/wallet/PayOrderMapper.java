package cn.iocoder.yudao.module.transport.dal.mysql.wallet;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.PayOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayOrderMapper extends BaseMapperX<PayOrderDO> {
    default PayOrderDO selectByOrderNo(String orderNo) {
        return selectOne(PayOrderDO::getOrderNo, orderNo);
    }

    /**
     * 按 (user_id, request_no) 取幂等键命中的那笔。
     * 带上 user_id 是刻意的：幂等键由客户端生成，别人的键不能复用成自己的订单。
     */
    default PayOrderDO selectByRequestNo(Long userId, String requestNo) {
        return selectOne(PayOrderDO::getUserId, userId, PayOrderDO::getRequestNo, requestNo);
    }
}
