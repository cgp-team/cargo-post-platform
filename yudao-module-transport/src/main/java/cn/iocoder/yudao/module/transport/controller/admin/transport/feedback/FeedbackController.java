package cn.iocoder.yudao.module.transport.controller.admin.transport.feedback;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.feedback.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.feedback.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 意见反馈")
@RestController
@RequestMapping("/transport/feedback")
@Validated
public class FeedbackController {
    @Resource private FeedbackService feedbackService;

    @PutMapping("/reply")
    @Operation(summary = "回复意见反馈")
    @PreAuthorize("@ss.hasPermission('transport:feedback:reply')")
    public CommonResult<Boolean> reply(@Valid @RequestBody FeedbackReplyReqVO reqVO) {
        feedbackService.reply(reqVO); return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得意见反馈")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:feedback:query')")
    public CommonResult<FeedbackRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(feedbackService.get(id), FeedbackRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得意见反馈分页")
    @PreAuthorize("@ss.hasPermission('transport:feedback:query')")
    public CommonResult<PageResult<FeedbackRespVO>> page(@Valid FeedbackPageReqVO reqVO) {
        return success(BeanUtils.toBean(feedbackService.getPage(reqVO), FeedbackRespVO.class));
    }
}
