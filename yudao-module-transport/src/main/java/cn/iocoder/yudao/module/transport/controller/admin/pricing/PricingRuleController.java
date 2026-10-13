package cn.iocoder.yudao.module.transport.controller.admin.pricing;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.pricing.vo.PricingRuleRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.pricing.vo.PricingRuleSaveReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.PricingRuleDO;
import cn.iocoder.yudao.module.transport.service.dispatch.PricingRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 运输计价规则")
@RestController
@RequestMapping("/transport/pricing-rule")
@Validated
public class PricingRuleController {

    @Resource private PricingRuleService pricingRuleService;

    @GetMapping("/get")
    @Operation(summary = "获得当前生效的计价规则（单行配置；库里没配时返回默认值并带 configured=false）")
    @PreAuthorize("@ss.hasPermission('transport:pricing-rule:query')")
    public CommonResult<PricingRuleRespVO> getPricingRule() {
        PricingRuleDO configured = pricingRuleService.getConfiguredRule();
        PricingRuleRespVO vo = BeanUtils.toBean(
                configured != null ? configured : PricingRuleService.defaultRule(), PricingRuleRespVO.class);
        vo.setConfigured(configured != null);
        return success(vo);
    }

    @PutMapping("/save")
    @Operation(summary = "保存计价规则（有配置行则改，无则插入——按 id 升序首条生效，所以只维护一行）")
    @PreAuthorize("@ss.hasPermission('transport:pricing-rule:save')")
    public CommonResult<Boolean> savePricingRule(@Valid @RequestBody PricingRuleSaveReqVO reqVO) {
        pricingRuleService.saveRule(reqVO);
        return success(true);
    }
}
