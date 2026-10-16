package cn.iocoder.yudao.module.aiassist.controller.admin.savedquery;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.idempotent.core.annotation.Idempotent;
import cn.iocoder.yudao.framework.idempotent.core.keyresolver.impl.UserIdempotentKeyResolver;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQueryPageReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQueryRespVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.savedquery.vo.AiSavedQuerySaveReqVO;
import cn.iocoder.yudao.module.aiassist.service.savedquery.AiSavedQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 保存的查询条件")
@RestController
@RequestMapping("/transport/ai/saved-query")
@Validated
public class AiSavedQueryController {

    @Resource
    private AiSavedQueryService aiSavedQueryService;

    @PostMapping("/create")
    @Operation(summary = "创建保存的查询条件")
    @PreAuthorize("@ss.hasPermission('transport:ai:saved-query:create')")
    @Idempotent(timeout = 10, timeUnit = TimeUnit.SECONDS, keyResolver = UserIdempotentKeyResolver.class,
            message = "该条件正在保存中，请勿重复提交（重复提交会存下两条同名条件，服务层没有查重）")
    public CommonResult<Long> create(@Valid @RequestBody AiSavedQuerySaveReqVO reqVO) {
        return success(aiSavedQueryService.createQuery(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "修改保存的查询条件")
    @PreAuthorize("@ss.hasPermission('transport:ai:saved-query:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody AiSavedQuerySaveReqVO reqVO) {
        aiSavedQueryService.updateQuery(reqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除保存的查询条件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:ai:saved-query:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        aiSavedQueryService.deleteQuery(id);
        return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得保存的查询条件")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:ai:saved-query:query')")
    public CommonResult<AiSavedQueryRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(aiSavedQueryService.getQuery(id), AiSavedQueryRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得保存的查询条件分页")
    @PreAuthorize("@ss.hasPermission('transport:ai:saved-query:query')")
    public CommonResult<PageResult<AiSavedQueryRespVO>> page(@Valid AiSavedQueryPageReqVO reqVO) {
        return success(BeanUtils.toBean(aiSavedQueryService.getQueryPage(reqVO), AiSavedQueryRespVO.class));
    }

}
