package cn.iocoder.yudao.module.aiassist.dal.mysql.invocation;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogPageReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiInvocationLogMapper extends BaseMapperX<AiInvocationLogDO> {

    default PageResult<AiInvocationLogDO> selectPage(AiInvocationLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiInvocationLogDO>()
                .eqIfPresent(AiInvocationLogDO::getScene, reqVO.getScene())
                .eqIfPresent(AiInvocationLogDO::getStatus, reqVO.getStatus())
                .eqIfPresent(AiInvocationLogDO::getOperatorId, reqVO.getOperatorId())
                .orderByDesc(AiInvocationLogDO::getId));
    }

}
