package cn.iocoder.yudao.module.transport.dal.mysql.route;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface RouteStationMapper extends BaseMapperX<RouteStationDO> {

    default List<RouteStationDO> selectListByRouteIds(Collection<Long> routeIds) {
        return selectList(new LambdaQueryWrapperX<RouteStationDO>()
                .inIfPresent(RouteStationDO::getRouteId, routeIds)
                .orderByAsc(RouteStationDO::getRouteId)
                .orderByAsc(RouteStationDO::getSequenceNo));
    }

    /** 单条线路的站点序列（按访问顺序升序） */
    default List<RouteStationDO> selectListByRouteId(Long routeId) {
        return selectList(new LambdaQueryWrapperX<RouteStationDO>()
                .eq(RouteStationDO::getRouteId, routeId)
                .orderByAsc(RouteStationDO::getSequenceNo));
    }
}
