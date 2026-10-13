package cn.iocoder.yudao.module.transport.dal.mysql.wallet;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.wallet.PayOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayOrderMapper extends BaseMapperX<PayOrderDO> {
    default PayOrderDO selectByOrderNo(String orderNo) {
        return selectOne(PayOrderDO::getOrderNo, orderNo);
    }
}
