package cn.iocoder.yudao.module.aiassist.controller.admin.capability;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiassist.controller.admin.capability.vo.AiCapabilityStatusRespVO;
import cn.iocoder.yudao.module.aiassist.service.capability.AiCapabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * AI 能力状态（契约 §7 / 菜单 7131）。只读；没有端点时这里如实说"未就绪"。
 */
@Tag(name = "管理后台 - AI 能力状态")
@RestController
@RequestMapping("/transport/ai/capability")
@Validated
public class AiCapabilityController {

    @Resource
    private AiCapabilityService aiCapabilityService;

    @GetMapping("/status")
    @Operation(summary = "获得 AI 能力状态（只读，不发起模型调用）")
    @PreAuthorize("@ss.hasPermission('transport:ai:capability:query')")
    public CommonResult<AiCapabilityStatusRespVO> status() {
        return success(aiCapabilityService.status());
    }

}
