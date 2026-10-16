package cn.iocoder.yudao.module.aiassist.service.savedquery;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQueryPageReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQuerySaveReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.savedquery.AiSavedQueryDO;
import cn.iocoder.yudao.module.aiassist.dal.mysql.savedquery.AiSavedQueryMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.aiassist.enums.ErrorCodeConstants.SAVED_QUERY_NOT_EXISTS;

@Service
@Validated
public class AiSavedQueryServiceImpl implements AiSavedQueryService {

    @Resource
    private AiSavedQueryMapper aiSavedQueryMapper;

    @Override
    public Long createQuery(AiSavedQuerySaveReqVO reqVO) {
        AiSavedQueryDO entity = BeanUtils.toBean(reqVO, AiSavedQueryDO.class);
        aiSavedQueryMapper.insert(entity);
        return entity.getId();
    }

    @Override
    public void updateQuery(AiSavedQuerySaveReqVO reqVO) {
        validateExists(reqVO.getId());
        aiSavedQueryMapper.updateById(BeanUtils.toBean(reqVO, AiSavedQueryDO.class));
    }

    @Override
    public void deleteQuery(Long id) {
        validateExists(id);
        aiSavedQueryMapper.deleteById(id);
    }

    @Override
    public AiSavedQueryDO getQuery(Long id) {
        return validateExists(id);
    }

    @Override
    public PageResult<AiSavedQueryDO> getQueryPage(AiSavedQueryPageReqVO reqVO) {
        return aiSavedQueryMapper.selectPage(reqVO);
    }

    private AiSavedQueryDO validateExists(Long id) {
        if (id == null) {
            throw exception(SAVED_QUERY_NOT_EXISTS);
        }
        AiSavedQueryDO entity = aiSavedQueryMapper.selectById(id);
        if (entity == null) {
            throw exception(SAVED_QUERY_NOT_EXISTS);
        }
        return entity;
    }

}
