package cn.iocoder.yudao.module.transport.service.transport.route;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import jakarta.validation.Valid;

public interface RouteService {
    Long create(@Valid RouteCreateReqVO reqVO);
    void update(@Valid RouteUpdateReqVO reqVO);
    void delete(Long id);
    RouteDO get(Long id);
    PageResult<RouteDO> getPage(RoutePageReqVO reqVO);
}
