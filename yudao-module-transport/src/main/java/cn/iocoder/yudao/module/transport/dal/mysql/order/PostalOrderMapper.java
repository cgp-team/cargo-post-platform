package cn.iocoder.yudao.module.transport.dal.mysql.order;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PostalOrderMapper extends BaseMapperX<PostalOrderDO> {
}
