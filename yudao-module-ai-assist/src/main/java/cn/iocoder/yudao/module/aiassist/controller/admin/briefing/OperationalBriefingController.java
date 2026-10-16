package cn.iocoder.yudao.module.aiassist.controller.admin.briefing;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.briefing.vo.OperationalBriefingRespVO;
import cn.iocoder.yudao.module.aiassist.service.briefing.OperationalBriefingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 运营简报模板导出（契约 §6 第 4 项 / 菜单 7120）。
 *
 * <p>只读计算：把已取到的指标排成一份 Markdown 文档，不写库、不注入写口（契约 §0）。
 */
@Tag(name = "管理后台 - 运营简报模板")
@RestController
@RequestMapping("/transport/ai/briefing")
@Validated
public class OperationalBriefingController {

    @Resource
    private OperationalBriefingService operationalBriefingService;

    @PostMapping("/render")
    @Operation(summary = "把已取到的指标排成一份简报 Markdown（只读计算）")
    @PreAuthorize("@ss.hasPermission('transport:ai:saved-query:query')")
    public CommonResult<OperationalBriefingRespVO> render(@Valid @RequestBody OperationalBriefingReqVO reqVO) {
        return success(operationalBriefingService.render(reqVO));
    }

}
