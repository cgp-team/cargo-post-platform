package cn.iocoder.yudao.module.aiassist.dal.mysql.savedquery;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQueryPageReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.savedquery.AiSavedQueryDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AiSavedQueryMapper extends BaseMapperX<AiSavedQueryDO> {

    default PageResult<AiSavedQueryDO> selectPage(AiSavedQueryPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AiSavedQueryDO>()
                .likeIfPresent(AiSavedQueryDO::getName, reqVO.getName())
                .eqIfPresent(AiSavedQueryDO::getScene, reqVO.getScene())
                .orderByDesc(AiSavedQueryDO::getId));
    }

}
