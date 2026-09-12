package cn.iocoder.yudao.module.transport.dal.mysql.route;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.RoutePageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RouteMapper extends BaseMapperX<RouteDO> {
    default PageResult<RouteDO> selectPage(RoutePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<RouteDO>()
                .likeIfPresent(RouteDO::getRouteCode, reqVO.getRouteCode())
                .likeIfPresent(RouteDO::getRouteName, reqVO.getRouteName())
                .eqIfPresent(RouteDO::getSourceType, reqVO.getSourceType())
                .eqIfPresent(RouteDO::getServiceType, reqVO.getServiceType())
                .eqIfPresent(RouteDO::getStatus, reqVO.getStatus())
                .eqIfPresent(RouteDO::getDispatchEnabled, reqVO.getDispatchEnabled())
                .betweenIfPresent(RouteDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(RouteDO::getId));
    }

    /** 启用且可用于调度的线路编号（停用线路不得进入规划，需求 §38） */
    default java.util.List<Long> selectEnabledDispatchRouteIds() {
        return selectList(new LambdaQueryWrapperX<RouteDO>()
                .eq(RouteDO::getStatus, 0)
                .eq(RouteDO::getDispatchEnabled, true))
                .stream().map(RouteDO::getId).toList();
    }
}
