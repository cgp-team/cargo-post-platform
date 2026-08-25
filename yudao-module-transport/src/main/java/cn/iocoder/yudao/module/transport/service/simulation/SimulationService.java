package cn.iocoder.yudao.module.transport.service.simulation;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.PlanItemActionEnum;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmRouteRespDTO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 模拟运营编排：从调度方案（任务段）+ 站点坐标 + 真实道路 polyline 构建模拟段，
 * 驱动 {@link SimulationEngine} 前进；监控侧统一经引擎取 SIMULATED 位置。
 *
 * 沿真实道路 polyline 移动（/api/v1/route 提供）；高德不可用时回退两点直线，
 * 明确 provider=euclidean 不伪装真实道路。REAL GPS 始终优先于 SIMULATED。
 */
@Service
public class SimulationService {

    @Resource private SimulationEngine simulationEngine;
    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private StationMapper stationMapper;
    @Resource private AlgorithmClient algorithmClient;

    /** 启动某方案某车辆的模拟（Start）。simulationEnabled=false 时引擎内 no-op。 */
    public void start(Long planId, Long vehicleId, double multiplier) {
        List<SimulationEngine.SimSegment> segments = buildSegments(planId, vehicleId);
        if (segments == null || segments.isEmpty()) {
            return;
        }
        DispatchPlanDO plan = dispatchPlanMapper.selectById(planId);
        simulationEngine.start(planId, vehicleId,
                plan != null && plan.getTaskWindowStart() != null ? plan.getTaskWindowStart() : null,
                segments, multiplier);
    }

    public void pause(Long vehicleId) { simulationEngine.pause(vehicleId); }

    public void resume(Long vehicleId) { simulationEngine.resume(vehicleId); }

    public void reset(Long vehicleId) { simulationEngine.reset(vehicleId); }

    public void setSpeed(Long vehicleId, double multiplier) { simulationEngine.setSpeed(vehicleId, multiplier); }

    /** 引擎当前推进结果（监控用）；无运行返回 null */
    public SimulationEngine.SimTick tick(Long vehicleId) { return simulationEngine.tick(vehicleId); }

    public SimulationEngine.SimRun getRun(Long vehicleId) { return simulationEngine.getRun(vehicleId); }

    /** 模拟运行状态（管理端控制页轮询）；无运行返回 null */
    public cn.iocoder.yudao.module.transport.controller.admin.simulation.SimulationStatusRespVO getStatus(Long vehicleId) {
        SimulationEngine.SimRun run = simulationEngine.getRun(vehicleId);
        if (run == null) {
            return null;
        }
        SimulationEngine.SimTick tick = simulationEngine.tick(vehicleId);
        cn.iocoder.yudao.module.transport.controller.admin.simulation.SimulationStatusRespVO vo =
                new cn.iocoder.yudao.module.transport.controller.admin.simulation.SimulationStatusRespVO();
        vo.setVehicleId(vehicleId);
        vo.setPlanId(run.getPlanId());
        vo.setStatus(run.getStatus());
        vo.setStatusName(statusName(run.getStatus()));
        vo.setMultiplier(run.getMultiplier());
        vo.setSimSeconds(run.currentSimSeconds());
        vo.setTotalSimSeconds(run.getTotalSimSeconds());
        if (tick != null) {
            vo.setCurrentStationName(tick.getStationName());
            vo.setArrived(tick.isArrived());
        }
        return vo;
    }

    private String statusName(int status) {
        return switch (status) {
            case SimulationEngine.STATUS_RUNNING -> "运行中";
            case SimulationEngine.STATUS_PAUSED -> "已暂停";
            case SimulationEngine.STATUS_COMPLETED -> "已完成";
            default -> "待启动";
        };
    }

    // ==================== 任务段 → 模拟段 ====================

    /**
     * 从方案明细构建有序模拟段：明细顺序 = 车辆经停顺序；
     * 每段 = 上一站→本站，行驶秒取明细 segment_duration_seconds（无则按里程÷均速估算），
     * 本站作业秒取 service_duration_seconds；polyline 走算法 /route（高德真实道路），失败回退两点直线。
     */
    private List<SimulationEngine.SimSegment> buildSegments(Long planId, Long vehicleId) {
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getPlanId, planId)
                .eq(DispatchPlanItemDO::getVehicleId, vehicleId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
        if (items.size() < 2) {
            return null;
        }
        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, StationDO> stationMap = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectBatchIds(stationIds).stream()
                        .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        List<SimulationEngine.SimSegment> segments = new ArrayList<>();
        long cumSeconds = 0;
        for (int i = 1; i < items.size(); i++) {
            DispatchPlanItemDO prev = items.get(i - 1);
            DispatchPlanItemDO cur = items.get(i);
            StationDO from = prev.getStationId() != null ? stationMap.get(prev.getStationId()) : null;
            StationDO to = cur.getStationId() != null ? stationMap.get(cur.getStationId()) : null;
            List<double[]> polyline = fetchPolyline(from, to);
            long travelSeconds = cur.getSegmentDurationSeconds() != null
                    ? cur.getSegmentDurationSeconds() : estimateSeconds(from, to);
            cumSeconds += travelSeconds;
            long serviceSeconds = cur.getServiceDurationSeconds() != null ? cur.getServiceDurationSeconds() : 0;
            boolean terminal = Objects.equals(cur.getActionType(), PlanItemActionEnum.RETURN.getAction());
            String stationName = to != null ? to.getStationName() : "";
            segments.add(new SimulationEngine.SimSegment(cur.getStationId(), stationName,
                    polyline, cumSeconds, serviceSeconds, terminal));
        }
        return segments;
    }

    /** 真实道路 polyline：算法 /route 返回坐标点；不可用/失败回退两点直线（明确 euclidean） */
    private List<double[]> fetchPolyline(StationDO from, StationDO to) {
        if (from == null || to == null || from.getLongitude() == null || from.getLatitude() == null
                || to.getLongitude() == null || to.getLatitude() == null) {
            return List.of(new double[]{0, 0}, new double[]{0, 0});
        }
        List<double[]> polyline = null;
        try {
            AlgorithmRouteRespDTO route = algorithmClient.route(AlgorithmRouteReqDTO.builder()
                    .origin(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(from.getLongitude().doubleValue()).latitude(from.getLatitude().doubleValue()).build())
                    .destination(AlgorithmRouteReqDTO.RoutePoint.builder()
                            .longitude(to.getLongitude().doubleValue()).latitude(to.getLatitude().doubleValue()).build())
                    .build());
            if (route != null && Boolean.TRUE.equals(route.getAvailable()) && route.getPolyline() != null
                    && route.getPolyline().size() >= 2) {
                polyline = route.getPolyline().stream()
                        .map(p -> new double[]{p.getLongitude(), p.getLatitude()})
                        .collect(Collectors.toList());
            }
        } catch (Exception ignored) {
            // 算法不可用：走直线兜底
        }
        if (polyline == null) {
            polyline = List.of(new double[]{from.getLongitude().doubleValue(), from.getLatitude().doubleValue()},
                    new double[]{to.getLongitude().doubleValue(), to.getLatitude().doubleValue()});
        }
        return polyline;
    }

    /** 行驶秒兜底：两点 Haversine 公里 ÷ 25km/h（与计价规则默认均速一致） */
    private long estimateSeconds(StationDO from, StationDO to) {
        if (from == null || to == null) {
            return 0;
        }
        double meters = SimulationEngine.haversineMeters(
                from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
        return (long) Math.round(meters / 1000.0 / 25.0 * 3600);
    }
}
