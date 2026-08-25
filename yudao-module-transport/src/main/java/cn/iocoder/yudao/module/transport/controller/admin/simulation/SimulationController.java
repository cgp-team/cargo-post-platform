package cn.iocoder.yudao.module.transport.controller.admin.simulation;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 模拟运营控制（开发/演示环境）。生产 simulationEnabled=false 时全部 no-op。
 */
@Tag(name = "管理后台 - 模拟运营控制")
@RestController
@RequestMapping("/transport/simulation")
@Validated
public class SimulationController {

    @Resource private SimulationService simulationService;

    @PostMapping("/start")
    @Operation(summary = "启动模拟：按方案(任务段)+车辆沿真实道路推进")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<Boolean> start(@RequestParam("planId") Long planId,
                                       @RequestParam("vehicleId") Long vehicleId,
                                       @RequestParam(value = "multiplier", defaultValue = "10") Double multiplier) {
        simulationService.start(planId, vehicleId, multiplier);
        return success(true);
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停模拟")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<Boolean> pause(@RequestParam("vehicleId") Long vehicleId) {
        simulationService.pause(vehicleId);
        return success(true);
    }

    @PostMapping("/resume")
    @Operation(summary = "恢复模拟")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<Boolean> resume(@RequestParam("vehicleId") Long vehicleId) {
        simulationService.resume(vehicleId);
        return success(true);
    }

    @PostMapping("/reset")
    @Operation(summary = "重置模拟")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<Boolean> reset(@RequestParam("vehicleId") Long vehicleId) {
        simulationService.reset(vehicleId);
        return success(true);
    }

    @PostMapping("/speed")
    @Operation(summary = "设置倍速（1x/5x/10x/30x/60x）")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<Boolean> setSpeed(@RequestParam("vehicleId") Long vehicleId,
                                          @RequestParam("multiplier") Double multiplier) {
        simulationService.setSpeed(vehicleId, multiplier);
        return success(true);
    }

}
