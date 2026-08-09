package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchPlanPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DispatchPlanMapper extends BaseMapperX<DispatchPlanDO> {
    default PageResult<DispatchPlanDO> selectPage(DispatchPlanPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<DispatchPlanDO>()
                .eqIfPresent(DispatchPlanDO::getStatus, reqVO.getStatus())
                .eqIfPresent(DispatchPlanDO::getMode, reqVO.getMode())
                .betweenIfPresent(DispatchPlanDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(DispatchPlanDO::getId));
    }
}
