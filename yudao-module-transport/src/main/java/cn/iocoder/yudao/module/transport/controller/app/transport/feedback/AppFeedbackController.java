package cn.iocoder.yudao.module.transport.controller.app.transport.feedback;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.app.transport.feedback.vo.AppFeedbackCreateReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.feedback.vo.AppFeedbackRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.feedback.FeedbackDO;
import cn.iocoder.yudao.module.transport.service.transport.feedback.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 意见反馈")
@RestController
@RequestMapping("/transport/feedback")
@Validated
public class AppFeedbackController {
    @Resource private FeedbackService feedbackService;

    @PostMapping("/create")
    @Operation(summary = "提交意见反馈")
    public CommonResult<Long> create(@Valid @RequestBody AppFeedbackCreateReqVO reqVO) {
        return success(feedbackService.create(getLoginUserId(), reqVO));
    }

    @GetMapping("/page")
    @Operation(summary = "我的意见反馈分页")
    public CommonResult<PageResult<AppFeedbackRespVO>> page(PageParam pageParam) {
        PageResult<FeedbackDO> pageResult = feedbackService.getMyPage(getLoginUserId(), pageParam);
        return success(BeanUtils.toBean(pageResult, AppFeedbackRespVO.class));
    }
}
