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
                .betweenIfPresent(RouteDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(RouteDO::getId));
    }
}
