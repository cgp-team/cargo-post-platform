package cn.iocoder.yudao.module.transport.service.transport.feedback;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo.*;
import cn.iocoder.yudao.module.transport.controller.app.transport.feedback.vo.AppFeedbackCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.feedback.FeedbackDO;
import jakarta.validation.Valid;

public interface FeedbackService {
    /** 小程序提交意见反馈 */
    Long create(Long userId, @Valid AppFeedbackCreateReqVO reqVO);
    /** 小程序查询自己的反馈分页 */
    PageResult<FeedbackDO> getMyPage(Long userId, PageParam pageParam);
    FeedbackDO get(Long id);
    PageResult<FeedbackDO> getPage(FeedbackPageReqVO reqVO);
    /** 管理后台回复反馈（置为已回复） */
    void reply(@Valid FeedbackReplyReqVO reqVO);
}
