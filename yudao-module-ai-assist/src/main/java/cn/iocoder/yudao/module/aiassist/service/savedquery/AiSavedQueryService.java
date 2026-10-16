package cn.iocoder.yudao.module.aiassist.service.savedquery;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQueryPageReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQuerySaveReqVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.savedquery.AiSavedQueryDO;

/**
 * 保存的查询条件 Service（契约 §6 第 4 项）。
 */
public interface AiSavedQueryService {

    Long createQuery(AiSavedQuerySaveReqVO reqVO);

    void updateQuery(AiSavedQuerySaveReqVO reqVO);

    void deleteQuery(Long id);

    AiSavedQueryDO getQuery(Long id);

    PageResult<AiSavedQueryDO> getQueryPage(AiSavedQueryPageReqVO reqVO);

}
