package cn.iocoder.yudao.module.transport.controller.admin.developer;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.transport.controller.admin.developer.vo.DeveloperStatusRespVO;
import cn.iocoder.yudao.module.transport.service.developer.DeveloperModeService;
import cn.iocoder.yudao.module.transport.service.developer.DeveloperSimulationGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 开发者模式控制（管理后台）。
 *
 * 开发者模式是 per-user 的 Redis 状态，不替代 RBAC 权限。
 * 模拟运营需要同时满足：developerMode + environmentSimulationEnabled + RBAC permission。
 */
@Tag(name = "管理后台 - 开发者模式")
@RestController
@RequestMapping("/transport/developer")
@Validated
public class DeveloperController {

    @Resource
    private DeveloperModeService developerModeService;

    @Resource
    private DeveloperSimulationGuard guard;

    @GetMapping("/status")
    @Operation(summary = "获取开发者状态")
    @PreAuthorize("@ss.hasPermission('transport:developer:access')")
    public CommonResult<DeveloperStatusRespVO> status() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        DeveloperStatusRespVO respVO = new DeveloperStatusRespVO();
        respVO.setDeveloperMode(developerModeService.isDeveloperMode(userId));
        respVO.setEnvironmentSimulationEnabled(guard.isEnvironmentSimulationEnabled());
        // RBAC 权限检查：通过 SecurityFrameworkUtils 获取当前用户权限
        respVO.setCanViewSimulation(hasPermission("transport:simulation:view"));
        respVO.setCanControlSimulation(hasPermission("transport:simulation:control"));
        return success(respVO);
    }

    @PostMapping("/enable")
    @Operation(summary = "开启开发者模式")
    @PreAuthorize("@ss.hasPermission('transport:developer:access')")
    public CommonResult<Boolean> enable() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        developerModeService.enableDeveloperMode(userId);
        return success(true);
    }

    @PostMapping("/disable")
    @Operation(summary = "关闭开发者模式")
    @PreAuthorize("@ss.hasPermission('transport:developer:access')")
    public CommonResult<Boolean> disable() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        developerModeService.disableDeveloperMode(userId);
        return success(true);
    }

    /**
     * 检查当前用户是否拥有指定权限
     */
    private boolean hasPermission(String permission) {
        try {
            return SecurityFrameworkUtils.getAuthentication() != null
                    && SecurityFrameworkUtils.getAuthentication().getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals(permission));
        } catch (Exception e) {
            return false;
        }
    }

}
