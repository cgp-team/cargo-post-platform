package cn.iocoder.yudao.module.transport.convert.transport.shift;

import cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface ShiftConvert {
    ShiftConvert INSTANCE = Mappers.getMapper(ShiftConvert.class);
    ShiftDO convert(ShiftCreateReqVO bean);
    ShiftDO convert(ShiftUpdateReqVO bean);
}
