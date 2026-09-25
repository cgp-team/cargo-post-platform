package cn.iocoder.yudao.module.transport.service.bigscreen;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchSettlementReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.DispatchSettlementRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.service.dispatch.DispatchService;
import cn.iocoder.yudao.module.transport.service.monitoring.MonitoringService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智慧大屏聚合 Service 实现
 *
 * SEP-01：单接口聚合 + Redis 缓存（48s = 60s 刷新间隔 × 0.8）。
 * 子模块失败互不拖垮：结算失败置 null（前端如实标注），趋势/班次照常返回。
 */
@Service
@Validated
@Slf4j
public class BigScreenServiceImpl implements BigScreenService {

    /** 24 小时趋势的日期格式（自然小时） */
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private StationMapper stationMapper;
    @Resource private RouteMapper routeMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private MonitoringService monitoringService;
    @Resource private DispatchService dispatchService;

    @Override
    @Cacheable(cacheNames = "transport:bigscreen:overview#48s", key = "'v1'")
    public Map<String, Object> getOverview() {
        Map<String, Object> result = new HashMap<>();
        result.put("generatedAt", LocalDateTime.now());
        // ===== 摘要 KPI（与 /dashboard/summary 同口径） =====
        result.put("orderTotal", transportOrderMapper.selectCount(null));
        LocalDate today = LocalDate.now();
        result.put("orderToday", transportOrderMapper.selectCount(new LambdaQueryWrapperX<TransportOrderDO>()
                .ge(TransportOrderDO::getCreateTime, today.atStartOfDay())));
        result.put("orderAmountTotal", sumOrderAmount(null));
        result.put("orderAmountToday", sumOrderAmount(today));
        result.put("vehicleTotal", vehicleMapper.selectCount(null));
        result.put("stationCount", stationMapper.selectCount(null));
        result.put("routeCount", routeMapper.selectCount(null));
        result.put("driverTotal", driverMapper.selectCount(null));
        // ===== 今日班次 =====
        List<MonitoringShiftRespVO> shifts = monitoringService.getShiftExecution();
        Map<String, Object> shiftSummary = new HashMap<>();
        shiftSummary.put("total", shifts.size());
        shiftSummary.put("pending", shifts.stream().filter(s -> Integer.valueOf(0).equals(s.getStatus())).count());
        shiftSummary.put("inTransit", shifts.stream().filter(s -> Integer.valueOf(1).equals(s.getStatus())).count());
        shiftSummary.put("completed", shifts.stream().filter(s -> Integer.valueOf(2).equals(s.getStatus())).count());
        result.put("shiftSummary", shiftSummary);
        result.put("shiftExecution", shifts);
        // ===== 返程结算（今日）===== 失败互不拖垮：置 null 由前端如实标注
        result.put("settlement", buildTodaySettlement());
        // ===== 订单趋势（24 小时）+ 分布 =====
        result.put("hourlyTrend", buildHourlyTrend());
        result.put("typeDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                .select("order_type AS type", "COUNT(*) AS count")
                .groupBy("order_type")));
        result.put("statusDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                .select("status AS status", "COUNT(*) AS count")
                .groupBy("status")));
        return result;
    }

    /** 今日返程结算；范围 [今日 00:00, 明日 00:00)。任何异常降级为 null 并留痕，不影响其余模块 */
    private DispatchSettlementRespVO buildTodaySettlement() {
        LocalDate today = LocalDate.now();
        DispatchSettlementReqVO reqVO = new DispatchSettlementReqVO();
        reqVO.setBatchStart(today.atStartOfDay());
        reqVO.setBatchEnd(today.plusDays(1).atStartOfDay());
        try {
            return dispatchService.settlement(reqVO);
        } catch (Exception ex) {
            log.warn("[getOverview] 今日返程结算查询失败，大屏该模块降级为无数据: {}", ex.getMessage());
            return null;
        }
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

    /** 近 24 小时订单量与金额趋势（按自然小时聚合），无数据的小时补零（与 DashboardController.hour 口径一致） */
    private List<Map<String, Object>> buildHourlyTrend() {
        LocalDateTime start = LocalDate.now().minusDays(1).atStartOfDay();
        List<Map<String, Object>> rows = transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                .select("DATE_FORMAT(create_time, '%Y-%m-%d %H:00') AS date", "COUNT(*) AS count",
                        "IFNULL(SUM(total_amount), 0) AS amount")
                .ge("create_time", start)
                .groupBy("DATE_FORMAT(create_time, '%Y-%m-%d %H:00')"));
        Map<String, Map<String, Object>> byHour = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            String hour = start.plusHours(i).format(HOUR_FMT);
            Map<String, Object> zero = new HashMap<>();
            zero.put("date", hour);
            zero.put("count", 0L);
            zero.put("amount", BigDecimal.ZERO);
            byHour.put(hour, zero);
        }
        for (Map<String, Object> row : rows) {
            Object date = row.get("date");
            if (date != null && byHour.containsKey(date.toString())) {
                byHour.put(date.toString(), row);
            }
        }
        return new ArrayList<>(byHour.values());
    }
}
