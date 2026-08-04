package cn.iocoder.yudao.module.transport.convert.transport.driver;

import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface DriverConvert {
    DriverConvert INSTANCE = Mappers.getMapper(DriverConvert.class);
    DriverDO convert(DriverCreateReqVO bean);
    DriverDO convert(DriverUpdateReqVO bean);
}
