package cn.iocoder.yudao.module.transport.convert.transport.vehicle;

import cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface VehicleConvert {
    VehicleConvert INSTANCE = Mappers.getMapper(VehicleConvert.class);
    VehicleDO convert(VehicleCreateReqVO bean);
    VehicleDO convert(VehicleUpdateReqVO bean);
}
