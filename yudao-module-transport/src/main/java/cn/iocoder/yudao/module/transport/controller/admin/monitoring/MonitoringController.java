package cn.iocoder.yudao.module.transport.controller.admin.monitoring;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.service.monitoring.MonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 车辆监控")
@RestController
@RequestMapping("/transport/monitoring")
@Validated
public class MonitoringController {

    @Resource private MonitoringService monitoringService;

    @GetMapping("/map-data")
    @Operation(summary = "获取地图图层数据（站点与线路）")
    @PreAuthorize("@ss.hasPermission('transport:monitoring:query')")
    public CommonResult<MonitoringMapDataRespVO> getMapData() {
        return success(monitoringService.getMapData());
    }

    @GetMapping("/vehicles")
    @Operation(summary = "获取车辆实时位置（班次时间模拟插值）")
    @PreAuthorize("@ss.hasPermission('transport:monitoring:query')")
    public CommonResult<List<MonitoringVehicleRespVO>> getRealtimeVehicles() {
        return success(monitoringService.getRealtimeVehicles());
    }

    @GetMapping("/shift-execution")
    @Operation(summary = "获取今日班次执行状态")
    @PreAuthorize("@ss.hasPermission('transport:monitoring:query')")
    public CommonResult<List<MonitoringShiftRespVO>> getShiftExecution() {
        return success(monitoringService.getShiftExecution());
    }
}
