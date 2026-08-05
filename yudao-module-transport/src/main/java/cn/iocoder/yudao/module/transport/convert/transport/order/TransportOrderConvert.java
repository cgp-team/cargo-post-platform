package cn.iocoder.yudao.module.transport.convert.transport.order;

import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface TransportOrderConvert {
    TransportOrderConvert INSTANCE = Mappers.getMapper(TransportOrderConvert.class);
    TransportOrderDO convert(TransportOrderCreateReqVO bean);
    TransportOrderDO convert(TransportOrderUpdateReqVO bean);
}
