package cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckReqVO;
import cn.iocoder.yudao.module.aiassist.controller.admin.capacityconflict.vo.CapacityConflictCheckRespVO;
import cn.iocoder.yudao.module.aiassist.service.capacityconflict.CapacityConflictService;
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
 * 客货空间冲突检视（契约 §6 第 3 项 / 菜单 7110）。
 *
 * <p>⚠ 这是一个 POST，但它是**只读计算**：不写库、不改排班、不注入任何写口（契约 §0）。
 * 之所以用 POST 而不用 GET，是因为输入是一整组车辆与分段的结构化对象，塞进查询串会失真。
 */
@Tag(name = "管理后台 - 客货空间冲突检视")
@RestController
@RequestMapping("/transport/ai/capacity-conflict")
@Validated
public class CapacityConflictController {

    @Resource
    private CapacityConflictService capacityConflictService;

    @PostMapping("/check")
    @Operation(summary = "执行一次客货空间冲突检视（只读计算）")
    @PreAuthorize("@ss.hasPermission('transport:ai:capacity-conflict:query')")
    public CommonResult<CapacityConflictCheckRespVO> check(@Valid @RequestBody CapacityConflictCheckReqVO reqVO) {
        return success(capacityConflictService.check(reqVO));
    }

}
