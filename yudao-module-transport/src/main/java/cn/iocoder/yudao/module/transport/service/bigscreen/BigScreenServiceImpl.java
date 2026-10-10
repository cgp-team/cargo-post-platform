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
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    @Resource private DistrictGeometryService districtGeometryService;

    @Override
    // 注意（2026-09-25 故障复盘）：此处曾加 @Cacheable（transport:bigscreen:overview#48s）。
    // 本方法返回 Map<String,Object> 并塞入 LocalDateTime.now()——Map 的 value 在
    // GenericJackson2JsonRedisSerializer（default typing NON_FINAL；LocalDateTime 是 final 类
    // 不留 @class）下无法还原为 LocalDateTime，缓存命中即反序列化失败。同类问题已验证会使
    // /app-api/transport/bus/{realtime,lines,nearby} 100% 返回「系统异常」。
    // 大屏按 5min/60s 分层刷新，缓存收益有限，故取消。
    // 若将来确需缓存：改缓存 JSON 字符串，并补一次"写入→读出"往返测试。
    public Map<String, Object> getOverview(String district) {
        Map<String, Object> result = new HashMap<>();
        result.put("generatedAt", LocalDateTime.now());
        // 划片区口径：district 非空时订单类指标按「取货站点 → 区县多边形」归属过滤；为空时保持全局 SQL 聚合
        final String scopedDistrict = (district == null || district.isBlank()) ? null : district.trim();
        result.put("district", scopedDistrict);
        // ===== 摘要 KPI（与 /dashboard/summary 同口径） =====
        LocalDate today = LocalDate.now();
        // 划片区：先解析区内站点集合（走 DistrictGeometryService 的站点→区县缓存，不再每请求全站点射线法）
        final Set<Long> districtStationIds = scopedDistrict != null ? stationIdsInDistrict(scopedDistrict) : null;
        if (scopedDistrict != null) {
            // 订单统计全部下推 SQL（与全局分支同构）；区内无站点时短路置零，避免 IN () 空集合
            result.put("stationCount", (long) districtStationIds.size());
            if (districtStationIds.isEmpty()) {
                result.put("orderTotal", 0L);
                result.put("orderToday", 0L);
                result.put("orderAmountTotal", BigDecimal.ZERO);
                result.put("orderAmountToday", BigDecimal.ZERO);
            } else {
                result.put("orderTotal", transportOrderMapper.selectCount(new LambdaQueryWrapperX<TransportOrderDO>()
                        .in(TransportOrderDO::getPickupStationId, districtStationIds)));
                result.put("orderToday", transportOrderMapper.selectCount(new LambdaQueryWrapperX<TransportOrderDO>()
                        .in(TransportOrderDO::getPickupStationId, districtStationIds)
                        .ge(TransportOrderDO::getCreateTime, today.atStartOfDay())));
                result.put("orderAmountTotal", sumOrderAmount(null, districtStationIds));
                result.put("orderAmountToday", sumOrderAmount(today, districtStationIds));
            }
        } else {
            result.put("orderTotal", transportOrderMapper.selectCount(null));
            result.put("orderToday", transportOrderMapper.selectCount(new LambdaQueryWrapperX<TransportOrderDO>()
                    .ge(TransportOrderDO::getCreateTime, today.atStartOfDay())));
            result.put("orderAmountTotal", sumOrderAmount(null));
            result.put("orderAmountToday", sumOrderAmount(today));
            result.put("stationCount", stationMapper.selectCount(null));
        }
        // 以下无属地维度，始终为全局口径（前端划片区时标注「全局」）
        result.put("vehicleTotal", vehicleMapper.selectCount(null));
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
        // ===== 订单趋势（24 小时滚动窗口）+ 分布（划片区同样走 SQL 聚合，与全局分支同构） =====
        if (scopedDistrict != null) {
            if (districtStationIds.isEmpty()) {
                // 空集合短路：补零趋势 + 空分布（不下发 IN () 查询）
                result.put("hourlyTrend", zeroFilledHourlyTrend());
                result.put("typeDistribution", List.of());
                result.put("statusDistribution", List.of());
            } else {
                result.put("hourlyTrend", buildHourlyTrend(districtStationIds));
                result.put("typeDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                        .select("order_type AS type", "COUNT(*) AS count")
                        .in("pickup_station_id", districtStationIds)
                        .groupBy("order_type")));
                result.put("statusDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                        .select("status AS status", "COUNT(*) AS count")
                        .in("pickup_station_id", districtStationIds)
                        .groupBy("status")));
            }
        } else {
            result.put("hourlyTrend", buildHourlyTrend());
            result.put("typeDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                    .select("order_type AS type", "COUNT(*) AS count")
                    .groupBy("order_type")));
            result.put("statusDistribution", transportOrderMapper.selectMaps(new QueryWrapper<TransportOrderDO>()
                    .select("status AS status", "COUNT(*) AS count")
                    .groupBy("status")));
        }
        return result;
    }

    /**
     * 划片区：按区县多边形筛出该区县内的站点 id（站点坐标 GCJ-02，与边界资源同坐标系）。
     * 走 DistrictGeometryService 的「站点ID→区县」本地缓存（懒构建一次），不再每次请求全站点射线法。
     */
    private Set<Long> stationIdsInDistrict(String district) {
        Set<Long> ids = new HashSet<>();
        districtGeometryService.stationDistrictMap().forEach((stationId, name) -> {
            if (district.equals(name)) {
                ids.add(stationId);
            }
        });
        return ids;
    }

    /** 划片区空站点短路用：补零的 24 小时滚动窗口趋势（与 SQL 版同一窗口/桶键口径） */
    private List<Map<String, Object>> zeroFilledHourlyTrend() {
        return new ArrayList<>(zeroHourBuckets(hourlyWindowStart()).values());
    }

    /** 近 24 小时滚动窗口起点：当前整点 - 23 小时（含当前小时共 24 桶，覆盖「昨天此时 → 现在」） */
    private static LocalDateTime hourlyWindowStart() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(23);
    }

    /** 24 个自然小时桶（补零），桶键格式 yyyy-MM-dd HH:00 */
    private static Map<String, Map<String, Object>> zeroHourBuckets(LocalDateTime start) {
        Map<String, Map<String, Object>> byHour = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            String hour = start.plusHours(i).format(HOUR_FMT);
            Map<String, Object> zero = new HashMap<>();
            zero.put("date", hour);
            zero.put("count", 0L);
            zero.put("amount", BigDecimal.ZERO);
            byHour.put(hour, zero);
        }
        return byHour;
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
        return sumOrderAmount(date, null);
    }

    /** 订单金额汇总；date 非空时只统计当天，stationIds 非空时只统计区内订单（取货站点口径，调用方保证非空集合） */
    private BigDecimal sumOrderAmount(LocalDate date, Collection<Long> stationIds) {
        QueryWrapper<TransportOrderDO> query = new QueryWrapper<TransportOrderDO>()
                .select("IFNULL(SUM(total_amount), 0) AS amount");
        if (stationIds != null) {
            query.in("pickup_station_id", stationIds);
        }
        if (date != null) {
            query.ge("create_time", date.atStartOfDay())
                    .lt("create_time", date.plusDays(1).atStartOfDay());
        }
        List<Map<String, Object>> rows = transportOrderMapper.selectMaps(query);
        Object amount = rows.isEmpty() ? null : rows.get(0).get("amount");
        return amount instanceof BigDecimal bd ? bd : BigDecimal.ZERO;
    }

    /** 近 24 小时订单量与金额趋势（滚动窗口、按自然小时聚合），无数据的小时补零（与 DashboardController.hour 口径一致） */
    private List<Map<String, Object>> buildHourlyTrend() {
        return buildHourlyTrend(null);
    }

    /**
     * 近 24 小时滚动窗口趋势：起点 = 当前整点 - 23 小时（含当前小时共 24 桶），
     * 覆盖「昨天全天 → 今天现在」。stationIds 非空时只统计区内订单（调用方保证非空集合）。
     */
    private List<Map<String, Object>> buildHourlyTrend(Collection<Long> stationIds) {
        LocalDateTime start = hourlyWindowStart();
        QueryWrapper<TransportOrderDO> query = new QueryWrapper<TransportOrderDO>()
                .select("DATE_FORMAT(create_time, '%Y-%m-%d %H:00') AS date", "COUNT(*) AS count",
                        "IFNULL(SUM(total_amount), 0) AS amount")
                .ge("create_time", start);
        if (stationIds != null) {
            query.in("pickup_station_id", stationIds);
        }
        query.groupBy("DATE_FORMAT(create_time, '%Y-%m-%d %H:00')");
        List<Map<String, Object>> rows = transportOrderMapper.selectMaps(query);
        Map<String, Map<String, Object>> byHour = zeroHourBuckets(start);
        for (Map<String, Object> row : rows) {
            Object date = row.get("date");
            if (date != null && byHour.containsKey(date.toString())) {
                byHour.put(date.toString(), row);
            }
        }
        return new ArrayList<>(byHour.values());
    }
}
