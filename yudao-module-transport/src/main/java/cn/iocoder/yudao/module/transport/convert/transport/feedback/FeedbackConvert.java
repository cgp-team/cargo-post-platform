package cn.iocoder.yudao.module.transport.convert.transport.feedback;

import cn.iocoder.yudao.module.transport.controller.app.transport.feedback.vo.AppFeedbackCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.feedback.FeedbackDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface FeedbackConvert {
    FeedbackConvert INSTANCE = Mappers.getMapper(FeedbackConvert.class);
    FeedbackDO convert(AppFeedbackCreateReqVO bean);
}
