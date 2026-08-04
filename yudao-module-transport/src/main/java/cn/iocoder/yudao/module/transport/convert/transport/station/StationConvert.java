package cn.iocoder.yudao.module.transport.convert.transport.station;

import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface StationConvert {
    StationConvert INSTANCE = Mappers.getMapper(StationConvert.class);
    StationDO convert(StationCreateReqVO bean);
    StationDO convert(StationUpdateReqVO bean);
}
