package cn.iocoder.yudao.module.transport.service.transport.route;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import jakarta.validation.Valid;

import java.util.List;

public interface RouteService {
    Long create(@Valid RouteCreateReqVO reqVO);
    void update(@Valid RouteUpdateReqVO reqVO);
    void delete(Long id);
    RouteDO get(Long id);
    PageResult<RouteDO> getPage(RoutePageReqVO reqVO);
    List<RouteDO> getSimpleList();

    /** 线路经停站点（按站序升序，含站点名与坐标） */
    List<RouteStationRespVO> getRouteStations(Long routeId);

    /** 整线覆盖保存经停站点序列（顺序即数组顺序；计划分钟缺省按里程估算） */
    void saveRouteStations(RouteStationSaveReqVO reqVO);
}
