package cn.iocoder.yudao.module.transport.controller.admin.simulation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.service.developer.DeveloperSimulationGuard;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 模拟运营控制（管理后台）。
 *
 * 三重保护：
 * 1. RBAC: transport:simulation:view / transport:simulation:control（@PreAuthorize）
 * 2. developerMode: Redis per-user 状态（DeveloperSimulationGuard）
 * 3. environmentSimulationEnabled: transport.simulation.enabled 配置（控制接口才检查）
 *
 * status 接口只检查 developerMode + RBAC，不检查 environment，以便前端展示"环境未启用"状态。
 * 控制接口（start/pause/resume/reset/speed）必须三者同时满足。
 */
@Tag(name = "管理后台 - 模拟运营控制")
@RestController
@RequestMapping("/transport/simulation")
@Validated
public class SimulationController {

    @Resource private SimulationService simulationService;
    @Resource private DeveloperSimulationGuard guard;

    @PostMapping("/start")
    @Operation(summary = "启动模拟：按方案(任务段)+车辆沿真实道路推进")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> start(@RequestParam("planId") Long planId,
                                       @RequestParam("vehicleId") Long vehicleId,
                                       @RequestParam(value = "multiplier", defaultValue = "10") Double multiplier) {
        guard.requireSimulationControl();
        simulationService.start(planId, vehicleId, multiplier);
        return success(true);
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停模拟")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> pause(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationControl();
        simulationService.pause(vehicleId);
        return success(true);
    }

    @PostMapping("/resume")
    @Operation(summary = "恢复模拟")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> resume(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationControl();
        simulationService.resume(vehicleId);
        return success(true);
    }

    @PostMapping("/reset")
    @Operation(summary = "重置模拟")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> reset(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationControl();
        simulationService.reset(vehicleId);
        return success(true);
    }

    @PostMapping("/speed")
    @Operation(summary = "设置倍速（1x/5x/10x/30x/60x）")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> setSpeed(@RequestParam("vehicleId") Long vehicleId,
                                          @RequestParam("multiplier") Double multiplier) {
        guard.requireSimulationControl();
        simulationService.setSpeed(vehicleId, multiplier);
        return success(true);
    }

    @GetMapping("/status")
    @Operation(summary = "查询模拟运行状态（控制页轮询）")
    @PreAuthorize("@ss.hasPermission('transport:simulation:view')")
    public CommonResult<SimulationStatusRespVO> status(@RequestParam("vehicleId") Long vehicleId) {
        // 只检查 developerMode，不检查 environment。
        // environment=false 时 status 仍正常返回，前端据此展示"环境未启用"状态。
        guard.requireSimulationView();
        return success(simulationService.getStatus(vehicleId));
    }

}
