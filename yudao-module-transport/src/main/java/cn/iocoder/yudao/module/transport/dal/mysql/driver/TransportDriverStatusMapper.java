package cn.iocoder.yudao.module.transport.dal.mysql.driver;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.TransportDriverStatusDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransportDriverStatusMapper extends BaseMapperX<TransportDriverStatusDO> {

    /** 按司机查实时状态（唯一键 driver_id + tenant_id） */
    default TransportDriverStatusDO selectByDriverId(Long driverId) {
        return selectOne(new LambdaQueryWrapperX<TransportDriverStatusDO>()
                .eq(TransportDriverStatusDO::getDriverId, driverId)
                .last("LIMIT 1"));
    }

}
