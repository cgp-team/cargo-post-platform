package cn.iocoder.yudao.module.aiassist.controller.admin.invocation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogPageReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.invocation.vo.AiInvocationLogRespVO;
import cn.iocoder.yudao.module.aiassist.dal.dataobject.invocation.AiInvocationLogDO;
import cn.iocoder.yudao.module.aiassist.service.invocation.AiInvocationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * AI 调用日志（契约 §5 / 菜单 7132）。**只读** —— 这张表只许后端写、运维查，页面没有删除口。
 */
@Tag(name = "管理后台 - AI 调用日志")
@RestController
@RequestMapping("/transport/ai/invocation-log")
@Validated
public class AiInvocationLogController {

    @Resource
    private AiInvocationLogService invocationLogService;

    @GetMapping("/page")
    @Operation(summary = "获得 AI 调用日志分页")
    @PreAuthorize("@ss.hasPermission('transport:ai:invocation-log:query')")
    public CommonResult<PageResult<AiInvocationLogRespVO>> getPage(@Valid AiInvocationLogPageReqVO reqVO) {
        PageResult<AiInvocationLogDO> pageResult = invocationLogService.getPage(reqVO);
        return success(BeanUtils.toBean(pageResult, AiInvocationLogRespVO.class));
    }

}
