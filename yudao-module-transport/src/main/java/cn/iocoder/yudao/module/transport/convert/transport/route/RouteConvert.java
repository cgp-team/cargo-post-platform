package cn.iocoder.yudao.module.transport.convert.transport.route;

import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface RouteConvert {
    RouteConvert INSTANCE = Mappers.getMapper(RouteConvert.class);
    RouteDO convert(RouteCreateReqVO bean);
    RouteDO convert(RouteUpdateReqVO bean);
}
