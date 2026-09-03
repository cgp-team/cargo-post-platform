package cn.iocoder.yudao.module.transport.controller.admin.simulation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.simulation.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.simulation.SimulationRunDO;
import cn.iocoder.yudao.module.transport.service.developer.DeveloperSimulationGuard;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationRuntimeService;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 模拟运营控制（管理后台）。
 *
 * 三重保护：
 * 1. RBAC: transport:simulation:view / transport:simulation:control（@PreAuthorize）
 * 2. developerMode: Redis per-user 状态（DeveloperSimulationGuard）
 * 3. environmentSimulationEnabled: transport.simulation.enabled 配置（控制接口才检查）
 */
@Tag(name = "管理后台 - 模拟运营控制")
@RestController
@RequestMapping("/transport/simulation")
@Validated
public class SimulationController {

    @Resource private SimulationService simulationService;
    @Resource private SimulationRuntimeService runtimeService;
    @Resource private DeveloperSimulationGuard guard;

    // ==================== 模拟控制 ====================

    @PostMapping("/start")
    @Operation(summary = "启动模拟：按方案(任务段)+车辆沿真实道路推进")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> start(@RequestParam("planId") Long planId,
                                       @RequestParam("vehicleId") Long vehicleId,
                                       @RequestParam(value = "multiplier", defaultValue = "10") Double multiplier) {
        guard.requireSimulationControl();
        simulationService.start(planId, vehicleId, multiplier);
        // 创建运行记录
        runtimeService.createRun(planId, vehicleId, multiplier);
        return success(true);
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停模拟")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> pause(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationControl();
        simulationService.pause(vehicleId);
        runtimeService.updateRunStatusWithEvent(vehicleId, 2, "PAUSED", "模拟暂停", "模拟运行已暂停");
        return success(true);
    }

    @PostMapping("/resume")
    @Operation(summary = "恢复模拟")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> resume(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationControl();
        simulationService.resume(vehicleId);
        runtimeService.updateRunStatusWithEvent(vehicleId, 1, "RESUMED", "模拟恢复", "模拟运行已恢复");
        return success(true);
    }

    @PostMapping("/reset")
    @Operation(summary = "重置模拟")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> reset(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationControl();
        // 先完成运行记录
        SimulationRunDO activeRun = runtimeService.getActiveRun(vehicleId);
        if (activeRun != null) {
            runtimeService.updateRunStatusWithEvent(vehicleId, 4, "RESET", "模拟重置", "模拟运行已重置");
        }
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
        guard.requireSimulationView();
        return success(simulationService.getStatus(vehicleId));
    }

    // ==================== 运行时状态 ====================

    @GetMapping("/runtime")
    @Operation(summary = "获取模拟运行时完整状态（地图+统计+事件一体化）")
    @PreAuthorize("@ss.hasPermission('transport:simulation:view')")
    public CommonResult<SimulationRuntimeRespVO> runtime(@RequestParam("vehicleId") Long vehicleId) {
        guard.requireSimulationView();
        return success(runtimeService.getRuntime(vehicleId));
    }

    @GetMapping("/events")
    @Operation(summary = "获取模拟事件列表")
    @PreAuthorize("@ss.hasPermission('transport:simulation:view')")
    public CommonResult<List<SimulationEventRespVO>> events(@RequestParam("runId") Long runId) {
        guard.requireSimulationView();
        return success(runtimeService.getEvents(runId));
    }

    // ==================== 场景管理 ====================

    @GetMapping("/scenarios")
    @Operation(summary = "获取可用模拟场景列表")
    @PreAuthorize("@ss.hasPermission('transport:simulation:view')")
    public CommonResult<List<SimulationScenarioRespVO>> scenarios() {
        guard.requireSimulationView();
        return success(runtimeService.getScenarios());
    }

    @PostMapping("/scenario/apply")
    @Operation(summary = "应用场景到当前模拟运行")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> applyScenario(@Valid @RequestBody SimulationScenarioApplyReqVO reqVO) {
        guard.requireSimulationControl();
        runtimeService.applyScenario(reqVO.getVehicleId(), reqVO.getScenarioId());
        return success(true);
    }

    // ==================== 异常注入 ====================

    @PostMapping("/inject")
    @Operation(summary = "注入异常事件到模拟运行")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> inject(@Valid @RequestBody SimulationInjectReqVO reqVO) {
        guard.requireSimulationControl();
        runtimeService.injectEvent(reqVO.getVehicleId(), reqVO.getEventType(),
                reqVO.getDurationSeconds(), reqVO.getRemark());
        return success(true);
    }

    // ==================== 历史管理 ====================

    @GetMapping("/history")
    @Operation(summary = "获取模拟运行历史列表")
    @PreAuthorize("@ss.hasPermission('transport:simulation:view')")
    public CommonResult<PageResult<SimulationRunRespVO>> history(SimulationRunPageReqVO reqVO) {
        guard.requireSimulationView();
        return success(runtimeService.getRunPage(reqVO));
    }

    @GetMapping("/report")
    @Operation(summary = "获取模拟运行报告")
    @PreAuthorize("@ss.hasPermission('transport:simulation:view')")
    public CommonResult<SimulationReportRespVO> report(@RequestParam("runId") Long runId) {
        guard.requireSimulationView();
        return success(runtimeService.getReport(runId));
    }

    @DeleteMapping("/history")
    @Operation(summary = "删除模拟运行记录")
    @PreAuthorize("@ss.hasPermission('transport:simulation:control')")
    public CommonResult<Boolean> deleteHistory(@RequestParam("runId") Long runId) {
        guard.requireSimulationControl();
        runtimeService.deleteRun(runId);
        return success(true);
    }
}
