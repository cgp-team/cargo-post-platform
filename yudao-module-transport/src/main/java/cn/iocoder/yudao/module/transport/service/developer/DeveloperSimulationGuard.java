package cn.iocoder.yudao.module.transport.service.developer;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 模拟运营统一校验 Guard。
 *
 * 校验链（三者缺一不可）：
 * 1. RBAC: transport:simulation:view 或 transport:simulation:control（由 @PreAuthorize 在 Controller 层完成）
 * 2. developerMode: Redis per-user 状态
 * 3. environmentSimulationEnabled: transport.simulation.enabled 配置
 *
 * 注意：@PreAuthorize 在 Controller 方法上声明具体权限，本 Guard 负责 developerMode + environment 校验。
 */
@Service
public class DeveloperSimulationGuard {

    @Value("${transport.simulation.enabled:false}")
    private boolean environmentSimulationEnabled;

    @Resource
    private DeveloperModeService developerModeService;

    /**
     * 校验模拟查看权限（用于 GET /status 等只读操作）。
     * 只检查 developerMode，不检查 environmentSimulationEnabled。
     * 原因：status 需要在 environment=false 时仍能正常返回，以便前端展示"环境未启用"状态。
     * 调用前 Controller 已通过 @PreAuthorize 校验 transport:simulation:view。
     */
    public void requireSimulationView() {
        requireDeveloperMode();
    }

    /**
     * 校验模拟控制权限（用于 start/pause/resume/reset/speed 等写操作）。
     * 调用前 Controller 已通过 @PreAuthorize 校验 transport:simulation:control。
     */
    public void requireSimulationControl() {
        requireDeveloperMode();
        requireEnvironmentEnabled();
    }

    /**
     * 获取当前环境模拟能力状态（供前端展示用，不做校验）
     */
    public boolean isEnvironmentSimulationEnabled() {
        return environmentSimulationEnabled;
    }

    private void requireDeveloperMode() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (!developerModeService.isDeveloperMode(userId)) {
            throw exception(DEVELOPER_MODE_REQUIRED);
        }
    }

    private void requireEnvironmentEnabled() {
        if (!environmentSimulationEnabled) {
            throw exception(SIMULATION_DISABLED);
        }
    }

}
