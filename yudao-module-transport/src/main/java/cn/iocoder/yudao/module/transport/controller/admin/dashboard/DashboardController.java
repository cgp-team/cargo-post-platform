package cn.iocoder.yudao.module.transport.controller.admin.dashboard;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.service.monitoring.MonitoringService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private MonitoringService monitoringService;

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

    @GetMapping("/summary")
    @Operation(summary = "获取运营摘要（订单、营收、运力、班次执行）")
    @PreAuthorize("@ss.hasPermission('transport:dashboard:query')")
    public CommonResult<Map<String, Object>> summary() {
        Map<String, Object> result = new HashMap<>();
        // 订单与营收
        result.put("orderTotal", transportOrderMapper.selectCount(null));
        result.put("orderToday", transportOrderMapper.selectCount(new LambdaQueryWrapperX<TransportOrderDO>()
                .ge(TransportOrderDO::getCreateTime, LocalDate.now().atStartOfDay())));
        result.put("orderAmountTotal", sumOrderAmount(null));
        result.put("orderAmountToday", sumOrderAmount(LocalDate.now()));
        // 运力（在途/空闲来自监控模拟）
        result.put("vehicleTotal", vehicleMapper.selectCount(null));
        List<MonitoringVehicleRespVO> vehicles = monitoringService.getRealtimeVehicles();
        result.put("vehicleInTransit", vehicles.stream().filter(v -> Integer.valueOf(1).equals(v.getStatus())).count());
        result.put("vehicleIdle", vehicles.stream().filter(v -> Integer.valueOf(0).equals(v.getStatus())).count());
        result.put("vehicleDisabled", vehicles.stream().filter(v -> Integer.valueOf(2).equals(v.getStatus())).count());
        result.put("driverTotal", driverMapper.selectCount(null));
        // 今日班次执行
        List<MonitoringShiftRespVO> shifts = monitoringService.getShiftExecution();
        result.put("shiftTotal", shifts.size());
        result.put("shiftPending", shifts.stream().filter(s -> Integer.valueOf(0).equals(s.getStatus())).count());
        result.put("shiftInTransit", shifts.stream().filter(s -> Integer.valueOf(1).equals(s.getStatus())).count());
        result.put("shiftCompleted", shifts.stream().filter(s -> Integer.valueOf(2).equals(s.getStatus())).count());
        return success(result);
    }

    @GetMapping("/order-statistics")
    @Operation(summary = "获取订单统计（类型/状态分布、近7日趋势）")
    @PreAuthorize("@ss.hasPermission('transport:dashboard:query')")
    public CommonResult<Map<String, Object>> orderStatistics() {
        Map<String, Object> result = new HashMap<>();
        result.put("typeDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                .select("order_type AS type", "COUNT(*) AS count")
                .groupBy("order_type")));
        result.put("statusDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                .select("status AS status", "COUNT(*) AS count")
                .groupBy("status")));
        result.put("dailyTrend", buildDailyTrend());
        return success(result);
    }

    /** 订单金额汇总；date 非空时只统计当天 */
    private BigDecimal sumOrderAmount(LocalDate date) {
        QueryWrapper<TransportOrderDO> query = new QueryWrapper<TransportOrderDO>()
                .select("IFNULL(SUM(total_amount), 0) AS amount");
        if (date != null) {
            query.ge("create_time", date.atStartOfDay())
                    .lt("create_time", date.plusDays(1).atStartOfDay());
        }
        List<Map<String, Object>> rows = transportOrderMapper.selectMaps(query);
        Object amount = rows.isEmpty() ? null : rows.get(0).get("amount");
        return amount instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
    }

    /** 近 7 日订单量与金额趋势，无数据的日期补零 */
    private List<Map<String, Object>> buildDailyTrend() {
        LocalDate start = LocalDate.now().minusDays(6);
        List<Map<String, Object>> rows = transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                .select("DATE_FORMAT(create_time, '%Y-%m-%d') AS date", "COUNT(*) AS count",
                        "IFNULL(SUM(total_amount), 0) AS amount")
                .ge("create_time", start.atStartOfDay())
                .groupBy("DATE_FORMAT(create_time, '%Y-%m-%d')"));
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            String date = start.plusDays(i).toString();
            Map<String, Object> zero = new HashMap<>();
            zero.put("date", date);
            zero.put("count", 0L);
            zero.put("amount", BigDecimal.ZERO);
            byDate.put(date, zero);
        }
        for (Map<String, Object> row : rows) {
            Object date = row.get("date");
            if (date != null && byDate.containsKey(date.toString())) {
                byDate.put(date.toString(), row);
            }
        }
        return new ArrayList<>(byDate.values());
    }
}
