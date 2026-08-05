package cn.iocoder.yudao.module.transport.controller.admin.dashboard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 运营概览")
@RestController
@RequestMapping("/transport/dashboard")
@Validated
public class DashboardController {

    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private StationMapper stationMapper;
    @Resource private RouteMapper routeMapper;

    @GetMapping("/statistics")
    @Operation(summary = "获取运营统计数据")
    @PreAuthorize("@ss.hasPermission('transport:dashboard:query')")
    public CommonResult<Map<String, Object>> statistics() {
        Map<String, Object> result = new HashMap<>();
        result.put("vehicleCount", vehicleMapper.selectCount(null));
        result.put("driverCount", driverMapper.selectCount(null));
        result.put("stationCount", stationMapper.selectCount(null));
        result.put("routeCount", routeMapper.selectCount(null));
        return success(result);
    }
}
