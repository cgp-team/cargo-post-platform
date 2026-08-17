package cn.iocoder.yudao.module.transport.service.transport.feedback;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo.*;
import cn.iocoder.yudao.module.transport.controller.app.transport.feedback.vo.AppFeedbackCreateReqVO;
import cn.iocoder.yudao.module.transport.convert.transport.feedback.FeedbackConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.feedback.FeedbackDO;
import cn.iocoder.yudao.module.transport.dal.mysql.feedback.FeedbackMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.FEEDBACK_NOT_EXISTS;

@Service @Validated
public class FeedbackServiceImpl implements FeedbackService {
    @Resource private FeedbackMapper mapper;

    @Override public Long create(Long userId, AppFeedbackCreateReqVO reqVO) {
        FeedbackDO o = FeedbackConvert.INSTANCE.convert(reqVO);
        o.setUserId(userId);
        o.setStatus(0); // 待处理
        mapper.insert(o);
        return o.getId();
    }
    @Override public PageResult<FeedbackDO> getMyPage(Long userId, PageParam pageParam) {
        return mapper.selectPageByUserId(userId, pageParam);
    }
    @Override public FeedbackDO get(Long id) { return validateExists(id); }
    @Override public PageResult<FeedbackDO> getPage(FeedbackPageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public void reply(FeedbackReplyReqVO reqVO) {
        validateExists(reqVO.getId());
        mapper.updateById(FeedbackDO.builder()
                .id(reqVO.getId())
                .status(1) // 已回复
                .reply(reqVO.getReply())
                .replyTime(LocalDateTime.now())
                .build());
    }

    private FeedbackDO validateExists(Long id) {
        FeedbackDO o = mapper.selectById(id);
        if (o == null) throw exception(FEEDBACK_NOT_EXISTS);
        return o;
    }
}
