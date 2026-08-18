package cn.iocoder.yudao.module.transport.dal.mysql.feedback;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo.FeedbackPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.feedback.FeedbackDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FeedbackMapper extends BaseMapperX<FeedbackDO> {

    default PageResult<FeedbackDO> selectPage(FeedbackPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<FeedbackDO>()
                .likeIfPresent(FeedbackDO::getName, reqVO.getName())
                .likeIfPresent(FeedbackDO::getMobile, reqVO.getMobile())
                .eqIfPresent(FeedbackDO::getStatus, reqVO.getStatus())
                .orderByDesc(FeedbackDO::getId));
    }

    /** 会员自己的反馈分页（小程序"我的反馈"） */
    default PageResult<FeedbackDO> selectPageByUserId(Long userId, PageParam pageParam) {
        return selectPage(pageParam, new LambdaQueryWrapperX<FeedbackDO>()
                .eq(FeedbackDO::getUserId, userId)
                .orderByDesc(FeedbackDO::getId));
    }
}
