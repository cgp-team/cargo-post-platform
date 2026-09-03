package cn.iocoder.yudao.module.transport.service.simulation;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.simulation.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.simulation.SimulationEventDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.simulation.SimulationRunDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.simulation.SimulationScenarioDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.simulation.SimulationEventMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.simulation.SimulationRunMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.simulation.SimulationScenarioMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.PlanItemActionEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 模拟运行时服务：事件记录、历史管理、场景管理、运行报告。
 *
 * 与 SimulationService（引擎编排）分工：
 * - SimulationService：驱动 SimulationEngine 前进
 * - SimulationRuntimeService：持久化事件/历史 + 场景注入 + 报告生成
 */
@Service
@Validated
public class SimulationRuntimeService {

    @Resource private SimulationEngine simulationEngine;
    @Resource private SimulationRunMapper runMapper;
    @Resource private SimulationEventMapper eventMapper;
    @Resource private SimulationScenarioMapper scenarioMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private DriverVehicleMapper driverVehicleMapper;
    @Resource private DispatchPlanMapper planMapper;
    @Resource private DispatchPlanItemMapper planItemMapper;
    @Resource private StationMapper stationMapper;

    // ==================== 运行时状态 ====================

    /**
     * 获取模拟运行时完整状态（地图+统计+事件一体化）。
     * 同时检测状态变化并自动持久化事件。
     */
    public SimulationRuntimeRespVO getRuntime(Long vehicleId) {
        SimulationEngine.SimRun run = simulationEngine.getRun(vehicleId);
        if (run == null) {
            return null;
        }

        // 自动检测并持久化状态变化事件
        List<SimulationEngine.SimEvent> autoEvents = simulationEngine.tickWithEvents(vehicleId);
        persistEvents(vehicleId, run.getPlanId(), autoEvents);

        SimulationEngine.SimTick tick = simulationEngine.tick(vehicleId);
        SimulationRuntimeRespVO vo = new SimulationRuntimeRespVO();
        vo.setVehicleId(vehicleId);
        vo.setPlanId(run.getPlanId());
        vo.setStatus(run.getStatus());
        vo.setMultiplier(run.getMultiplier());
        vo.setSimSeconds(run.currentSimSeconds());
        vo.setTotalSimSeconds(run.getTotalSimSeconds());
        vo.setSegmentIndex(tick != null ? tick.getSegmentIndex() : null);

        // 车辆信息
        VehicleDO vehicle = vehicleMapper.selectById(vehicleId);
        vo.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);

        // 位置信息
        if (tick != null) {
            vo.setLongitude(tick.getLongitude());
            vo.setLatitude(tick.getLatitude());
            vo.setDataSource("SIMULATED");
            vo.setCurrentStationName(tick.getStationName());
            vo.setArrived(tick.isArrived());

            // 计算速度（从引擎段信息推导）
            List<SimulationEngine.SimSegment> segments = run.getSegments();
            if (tick.getSegmentIndex() < segments.size()) {
                SimulationEngine.SimSegment seg = segments.get(tick.getSegmentIndex());
                vo.setCurrentStationId(seg.getStationId());
                // 下一站
                int nextIdx = tick.getSegmentIndex() + 1;
                if (nextIdx < segments.size()) {
                    SimulationEngine.SimSegment nextSeg = segments.get(nextIdx);
                    vo.setNextStationId(nextSeg.getStationId());
                    vo.setNextStationName(nextSeg.getStationName());
                    // 距下一站距离
                    double distMeters = seg.lengthMeters();
                    vo.setDistanceToNextStation(distMeters / 1000.0);
                    // ETA
                    long remainSeconds = seg.getArrivalSimSeconds() - tick.getSimSeconds();
                    if (remainSeconds > 0) {
                        vo.setEtaMinutes(remainSeconds / 60.0 / run.getMultiplier());
                    }
                }
                // 速度估算
                if (seg.getArrivalSimSeconds() > 0) {
                    long segTravelSec = seg.getArrivalSimSeconds() -
                            (tick.getSegmentIndex() > 0 ? segments.get(tick.getSegmentIndex() - 1).getArrivalSimSeconds() + segments.get(tick.getSegmentIndex() - 1).getServiceSeconds() : 0);
                    if (segTravelSec > 0) {
                        vo.setSpeedKmh(seg.lengthMeters() / 1000.0 / (segTravelSec / 3600.0));
                    }
                }
            }
        }

        // 状态名
        vo.setStatusName(switch (run.getStatus()) {
            case SimulationEngine.STATUS_RUNNING -> "运行中";
            case SimulationEngine.STATUS_PAUSED -> "已暂停";
            case SimulationEngine.STATUS_COMPLETED -> "已完成";
            case SimulationEngine.STATUS_STOPPED -> "已停止";
            default -> "未知";
        });

        // 路线polyline
        List<SimulationEngine.SimSegment> segments = run.getSegments();
        List<List<Double>> polyline = new ArrayList<>();
        for (SimulationEngine.SimSegment seg : segments) {
            for (double[] p : seg.getPolyline()) {
                polyline.add(List.of(p[0], p[1]));
            }
        }
        vo.setPolyline(polyline);

        // 经停站点
        List<SimulationRuntimeRespVO.StopInfo> stops = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            SimulationEngine.SimSegment seg = segments.get(i);
            SimulationRuntimeRespVO.StopInfo stop = new SimulationRuntimeRespVO.StopInfo();
            stop.setStationId(seg.getStationId());
            stop.setStationName(seg.getStationName());
            if (!seg.getPolyline().isEmpty()) {
                double[] lastPoint = seg.getPolyline().get(seg.getPolyline().size() - 1);
                stop.setLongitude(lastPoint[0]);
                stop.setLatitude(lastPoint[1]);
            }
            stop.setVisitSequence(i);
            // 状态：已过/当前/待达
            if (tick != null) {
                if (i < tick.getSegmentIndex()) {
                    stop.setStatus(7); // COMPLETED
                    stop.setStatusName("已过");
                } else if (i == tick.getSegmentIndex()) {
                    stop.setStatus(tick.isArrived() ? 2 : 1); // ARRIVED / EN_ROUTE
                    stop.setStatusName(tick.isArrived() ? "停靠中" : "行驶中");
                } else {
                    stop.setStatus(0); // PENDING
                    stop.setStatusName("待达");
                }
            }
            stops.add(stop);
        }
        vo.setStops(stops);

        return vo;
    }

    // ==================== 事件管理 ====================

    /**
     * 记录模拟事件
     */
    public void recordEvent(Long runId, Long vehicleId, String eventType, int severity,
                            String title, String content, Long simSeconds,
                            Long stationId, String stationName,
                            Double longitude, Double latitude) {
        SimulationEventDO event = new SimulationEventDO();
        event.setRunId(runId);
        event.setVehicleId(vehicleId);
        event.setEventType(eventType);
        event.setSeverity(severity);
        event.setTitle(title);
        event.setContent(content);
        event.setSimSeconds(simSeconds);
        event.setStationId(stationId);
        event.setStationName(stationName);
        if (longitude != null) event.setLongitude(java.math.BigDecimal.valueOf(longitude));
        if (latitude != null) event.setLatitude(java.math.BigDecimal.valueOf(latitude));
        eventMapper.insert(event);
    }

    /**
     * 持久化引擎自动产生的事件（tickWithEvents 返回的事件）。
     * 根据 planId + vehicleId 查找对应的 SimulationRun。
     */
    private void persistEvents(Long vehicleId, Long planId, List<SimulationEngine.SimEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        SimulationRunDO run = getRunByPlanAndVehicle(planId, vehicleId);
        Long runId = run != null ? run.getId() : null;
        for (SimulationEngine.SimEvent evt : events) {
            recordEvent(runId, vehicleId, evt.getEventType(), evt.getSeverity(),
                    evt.getTitle(), evt.getContent(), evt.getSimSeconds(),
                    evt.getStationId(), evt.getStationName(), null, null);
        }
    }

    /**
     * 获取模拟事件列表
     */
    public List<SimulationEventRespVO> getEvents(Long runId) {
        List<SimulationEventDO> events = eventMapper.selectList(
                new LambdaQueryWrapperX<SimulationEventDO>()
                        .eq(SimulationEventDO::getRunId, runId)
                        .orderByAsc(SimulationEventDO::getSimSeconds));
        return events.stream().map(this::convertEvent).toList();
    }

    private SimulationEventRespVO convertEvent(SimulationEventDO e) {
        SimulationEventRespVO vo = new SimulationEventRespVO();
        vo.setId(e.getId());
        vo.setRunId(e.getRunId());
        vo.setEventType(e.getEventType());
        vo.setSeverity(e.getSeverity());
        vo.setTitle(e.getTitle());
        vo.setContent(e.getContent());
        vo.setSimSeconds(e.getSimSeconds());
        vo.setStationName(e.getStationName());
        vo.setCreateTime(e.getCreateTime());
        return vo;
    }

    // ==================== 历史管理 ====================

    /**
     * 创建模拟运行记录。
     * 同一车辆同一时间只允许一个活跃运行，重复 start 会终止旧运行。
     */
    public Long createRun(Long planId, Long vehicleId, Double multiplier) {
        // 终止该车辆之前的活跃运行
        abortActiveRunForVehicle(vehicleId);

        SimulationRunDO run = new SimulationRunDO();
        run.setPlanId(planId);
        run.setVehicleId(vehicleId);
        run.setMultiplier(multiplier);
        run.setStatus(1); // RUNNING
        run.setStartTime(LocalDateTime.now());

        // 获取司机信息
        List<DriverVehicleDO> bindings = driverVehicleMapper.selectList(
                new LambdaQueryWrapperX<DriverVehicleDO>()
                        .eq(DriverVehicleDO::getVehicleId, vehicleId)
                        .eq(DriverVehicleDO::getStatus, 1));
        if (!bindings.isEmpty()) {
            run.setDriverId(bindings.get(0).getDriverId());
        }

        // 统计站点和订单
        List<DispatchPlanItemDO> items = planItemMapper.selectList(
                new LambdaQueryWrapperX<DispatchPlanItemDO>()
                        .eq(DispatchPlanItemDO::getPlanId, planId)
                        .eq(DispatchPlanItemDO::getVehicleId, vehicleId));
        run.setStationCount(items.size());
        long orderCount = items.stream().filter(i -> i.getOrderId() != null).distinct().count();
        run.setOrderCount((int) orderCount);

        // 总里程
        DispatchPlanDO plan = planMapper.selectById(planId);
        if (plan != null && plan.getTotalDistance() != null) {
            run.setTotalDistanceKm(plan.getTotalDistance().doubleValue());
        }

        runMapper.insert(run);
        return run.getId();
    }

    /**
     * 终止指定车辆的活跃运行（状态为 RUNNING/PAUSED 的记录）
     */
    private void abortActiveRunForVehicle(Long vehicleId) {
        List<SimulationRunDO> activeRuns = runMapper.selectList(
                new LambdaQueryWrapperX<SimulationRunDO>()
                        .eq(SimulationRunDO::getVehicleId, vehicleId)
                        .in(SimulationRunDO::getStatus, 1, 2)); // RUNNING, PAUSED
        for (SimulationRunDO activeRun : activeRuns) {
            activeRun.setStatus(4); // TERMINATED
            activeRun.setEndTime(LocalDateTime.now());
            runMapper.updateById(activeRun);
            // 记录终止事件
            recordEvent(activeRun.getId(), vehicleId, "SYSTEM_TERMINATED", 1,
                    "运行被新任务终止", "新的模拟启动导致此运行被终止",
                    activeRun.getActualSimSeconds() != null ? activeRun.getActualSimSeconds() : 0,
                    null, null, null, null);
        }
    }

    /**
     * 更新运行状态
     */
    public void updateRunStatus(Long runId, int status) {
        SimulationRunDO run = runMapper.selectById(runId);
        if (run == null) return;
        run.setStatus(status);
        if (status == 3 || status == 4) { // COMPLETED / TERMINATED
            run.setEndTime(LocalDateTime.now());
        }
        runMapper.updateById(run);
    }

    /**
     * 按车辆ID获取活跃运行（RUNNING/PAUSED）
     */
    public SimulationRunDO getActiveRun(Long vehicleId) {
        return runMapper.selectOne(
                new LambdaQueryWrapperX<SimulationRunDO>()
                        .eq(SimulationRunDO::getVehicleId, vehicleId)
                        .in(SimulationRunDO::getStatus, 1, 2)
                        .orderByDesc(SimulationRunDO::getId)
                        .last("LIMIT 1"));
    }

    /**
     * 更新运行状态并记录事件
     */
    public void updateRunStatusWithEvent(Long vehicleId, int status, String eventType, String title, String content) {
        SimulationRunDO run = getActiveRun(vehicleId);
        if (run == null) return;
        run.setStatus(status);
        if (status == 3 || status == 4) {
            run.setEndTime(LocalDateTime.now());
        }
        runMapper.updateById(run);
        SimulationEngine.SimTick tick = simulationEngine.tick(vehicleId);
        Long simSeconds = tick != null ? tick.getSimSeconds() : 0;
        recordEvent(run.getId(), vehicleId, eventType, 0, title, content,
                simSeconds, null, tick != null ? tick.getStationName() : null, null, null);
    }

    /**
     * 完成运行并生成摘要
     */
    public void completeRun(Long runId, SimulationEngine.SimRun engineRun) {
        SimulationRunDO run = runMapper.selectById(runId);
        if (run == null) return;
        run.setStatus(3); // COMPLETED
        run.setEndTime(LocalDateTime.now());
        if (engineRun != null) {
            run.setActualSimSeconds(engineRun.currentSimSeconds());
        }
        // 统计异常事件数
        Long eventCount = eventMapper.selectCount(
                new LambdaQueryWrapperX<SimulationEventDO>()
                        .eq(SimulationEventDO::getRunId, runId)
                        .eq(SimulationEventDO::getSeverity, 2));
        run.setExceptionCount(eventCount.intValue());
        runMapper.updateById(run);
    }

    /**
     * 启动时恢复：将异常遗留的 RUNNING/PAUSED 记录标记为 ABORTED。
     * 应在应用启动时调用。
     */
    @jakarta.annotation.PostConstruct
    public void recoverOnStartup() {
        List<SimulationRunDO> orphanRuns = runMapper.selectList(
                new LambdaQueryWrapperX<SimulationRunDO>()
                        .in(SimulationRunDO::getStatus, 1, 2)); // RUNNING, PAUSED
        for (SimulationRunDO run : orphanRuns) {
            run.setStatus(4); // TERMINATED (system recovery)
            run.setEndTime(LocalDateTime.now());
            runMapper.updateById(run);
            recordEvent(run.getId(), run.getVehicleId(), "SYSTEM_RECOVERY", 1,
                    "系统重启恢复", "服务重启，运行状态已标记为终止",
                    run.getActualSimSeconds() != null ? run.getActualSimSeconds() : 0,
                    null, null, null, null);
        }
    }

    /**
     * 分页查询模拟运行历史
     */
    public PageResult<SimulationRunRespVO> getRunPage(SimulationRunPageReqVO reqVO) {
        LambdaQueryWrapperX<SimulationRunDO> query = new LambdaQueryWrapperX<SimulationRunDO>()
                .eqIfPresent(SimulationRunDO::getPlanId, reqVO.getPlanId())
                .eqIfPresent(SimulationRunDO::getVehicleId, reqVO.getVehicleId())
                .eqIfPresent(SimulationRunDO::getStatus, reqVO.getStatus())
                .orderByDesc(SimulationRunDO::getId);
        PageResult<SimulationRunDO> pageResult = runMapper.selectPage(reqVO, query);

        // 车辆信息
        Set<Long> vehicleIds = pageResult.getList().stream()
                .map(SimulationRunDO::getVehicleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> plateMap = vehicleIds.isEmpty() ? Map.of()
                : vehicleMapper.selectBatchIds(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleDO::getId, VehicleDO::getPlateNo, (a, b) -> a));

        // 场景信息
        Set<Long> scenarioIds = pageResult.getList().stream()
                .map(SimulationRunDO::getScenarioId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> scenarioMap = scenarioIds.isEmpty() ? Map.of()
                : scenarioMapper.selectBatchIds(scenarioIds).stream()
                .collect(Collectors.toMap(SimulationScenarioDO::getId, SimulationScenarioDO::getName, (a, b) -> a));

        List<SimulationRunRespVO> voList = pageResult.getList().stream().map(run -> {
            SimulationRunRespVO vo = new SimulationRunRespVO();
            vo.setId(run.getId());
            vo.setPlanId(run.getPlanId());
            vo.setVehicleId(run.getVehicleId());
            vo.setPlateNo(plateMap.get(run.getVehicleId()));
            vo.setScenarioName(scenarioMap.get(run.getScenarioId()));
            vo.setMultiplier(run.getMultiplier());
            vo.setStatus(run.getStatus());
            vo.setStatusName(runStatusName(run.getStatus()));
            vo.setStartTime(run.getStartTime());
            vo.setEndTime(run.getEndTime());
            vo.setTotalSimSeconds(run.getTotalSimSeconds());
            vo.setActualSimSeconds(run.getActualSimSeconds());
            vo.setStationCount(run.getStationCount());
            vo.setCompletedStationCount(run.getCompletedStationCount());
            vo.setExceptionCount(run.getExceptionCount());
            vo.setCreateTime(run.getCreateTime());
            return vo;
        }).toList();
        return new PageResult<>(voList, pageResult.getTotal());
    }

    /**
     * 获取运行报告
     */
    public SimulationReportRespVO getReport(Long runId) {
        SimulationRunDO run = runMapper.selectById(runId);
        if (run == null) return null;

        SimulationReportRespVO vo = new SimulationReportRespVO();
        vo.setRunId(run.getId());
        vo.setPlanId(run.getPlanId());
        vo.setVehicleId(run.getVehicleId());
        vo.setMultiplier(run.getMultiplier());
        vo.setStatus(run.getStatus());
        vo.setStatusName(runStatusName(run.getStatus()));
        vo.setStartTime(run.getStartTime());
        vo.setEndTime(run.getEndTime());
        vo.setTotalSimSeconds(run.getTotalSimSeconds());
        vo.setActualSimSeconds(run.getActualSimSeconds());
        vo.setTotalDistanceKm(run.getTotalDistanceKm());
        vo.setActualDistanceKm(run.getActualDistanceKm());
        vo.setStationCount(run.getStationCount());
        vo.setCompletedStationCount(run.getCompletedStationCount());
        vo.setOrderCount(run.getOrderCount());
        vo.setCompletedOrderCount(run.getCompletedOrderCount());
        vo.setExceptionCount(run.getExceptionCount());

        // 车辆信息
        VehicleDO vehicle = vehicleMapper.selectById(run.getVehicleId());
        vo.setPlateNo(vehicle != null ? vehicle.getPlateNo() : null);

        // 司机信息
        if (run.getDriverId() != null) {
            DriverDO driver = driverMapper.selectById(run.getDriverId());
            vo.setDriverName(driver != null ? driver.getName() : null);
        }

        // 场景信息
        if (run.getScenarioId() != null) {
            SimulationScenarioDO scenario = scenarioMapper.selectById(run.getScenarioId());
            vo.setScenarioName(scenario != null ? scenario.getName() : null);
        }

        // 事件列表
        vo.setEvents(getEvents(runId));

        return vo;
    }

    /**
     * 删除模拟运行记录
     */
    public void deleteRun(Long runId) {
        SimulationRunDO run = runMapper.selectById(runId);
        if (run == null) return;
        // 只允许删除已完成或已终止的记录
        if (run.getStatus() != 3 && run.getStatus() != 4) {
            throw new RuntimeException("只能删除已完成或已终止的模拟记录");
        }
        runMapper.deleteById(runId);
        // 删除关联事件
        eventMapper.delete(new LambdaQueryWrapperX<SimulationEventDO>()
                .eq(SimulationEventDO::getRunId, runId));
    }

    // ==================== 场景管理 ====================

    /**
     * 获取所有可用场景
     */
    public List<SimulationScenarioRespVO> getScenarios() {
        List<SimulationScenarioDO> scenarios = scenarioMapper.selectList(
                new LambdaQueryWrapperX<SimulationScenarioDO>()
                        .eq(SimulationScenarioDO::getEnabled, true)
                        .orderByAsc(SimulationScenarioDO::getId));
        return scenarios.stream().map(this::convertScenario).toList();
    }

    /**
     * 应用场景到模拟运行。场景会真正修改 SimulationEngine 的运行时状态。
     */
    public void applyScenario(Long vehicleId, Long scenarioId) {
        SimulationEngine.SimRun run = simulationEngine.getRun(vehicleId);
        if (run == null) {
            throw new RuntimeException("当前车辆没有运行中的模拟");
        }
        SimulationScenarioDO scenario = scenarioMapper.selectById(scenarioId);
        if (scenario == null) {
            throw new RuntimeException("场景不存在");
        }

        // 先重置运行时状态
        simulationEngine.resetRuntimeState(vehicleId);

        // 根据场景类型修改引擎运行时状态
        switch (scenario.getScenarioType()) {
            case "CONGESTION":
                simulationEngine.setTrafficFactor(vehicleId, 1.5); // 拥堵：旅行时间延长50%
                break;
            case "DELAY":
                simulationEngine.setDelaySeconds(vehicleId, 300); // 晚点：延迟5分钟
                break;
            case "GPS_LOST":
                simulationEngine.setGpsAvailable(vehicleId, false);
                break;
            case "FAULT":
                simulationEngine.setVehicleFault(vehicleId, true);
                break;
            case "DRIVER_OFFLINE":
                simulationEngine.setDriverOnline(vehicleId, false);
                break;
            case "NORMAL":
            default:
                // 默认状态，已重置
                break;
        }

        // 记录场景应用事件
        SimulationRunDO runDO = getRunByPlanAndVehicle(run.getPlanId(), vehicleId);
        if (runDO != null) {
            runDO.setScenarioId(scenarioId);
            runMapper.updateById(runDO);
            recordEvent(runDO.getId(), vehicleId, "SCENARIO_APPLIED", 0,
                    "应用场景: " + scenario.getName(), scenario.getDescription(),
                    run.currentSimSeconds(), null, null, null, null);
        }
    }

    private SimulationScenarioRespVO convertScenario(SimulationScenarioDO s) {
        SimulationScenarioRespVO vo = new SimulationScenarioRespVO();
        vo.setId(s.getId());
        vo.setName(s.getName());
        vo.setDescription(s.getDescription());
        vo.setScenarioType(s.getScenarioType());
        vo.setSeverity(s.getSeverity());
        vo.setBuiltin(s.getBuiltin());
        vo.setEnabled(s.getEnabled());
        return vo;
    }

    // ==================== 异常注入 ====================

    /**
     * 注入异常事件。会真正修改 SimulationEngine 的运行时状态。
     */
    public void injectEvent(Long vehicleId, String eventType, Long durationSeconds, String remark) {
        SimulationEngine.SimRun run = simulationEngine.getRun(vehicleId);
        if (run == null) {
            throw new RuntimeException("当前车辆没有运行中的模拟");
        }

        SimulationRunDO runDO = getRunByPlanAndVehicle(run.getPlanId(), vehicleId);
        Long runId = runDO != null ? runDO.getId() : null;

        long simSeconds = run.currentSimSeconds();
        SimulationEngine.SimTick tick = simulationEngine.tick(vehicleId);
        String stationName = tick != null ? tick.getStationName() : null;
        Double lon = tick != null ? tick.getLongitude() : null;
        Double lat = tick != null ? tick.getLatitude() : null;

        String title;
        String content;
        int severity;

        switch (eventType) {
            case "FAULT":
                title = "车辆故障";
                content = "车辆发生故障" + (remark != null ? "：" + remark : "");
                severity = 2;
                simulationEngine.setVehicleFault(vehicleId, true); // 真正停止推进
                break;
            case "GPS_LOST":
                title = "GPS 信号丢失";
                content = "GPS 信号丢失" + (remark != null ? "：" + remark : "");
                severity = 1;
                simulationEngine.setGpsAvailable(vehicleId, false); // 真正禁用GPS
                break;
            case "DRIVER_OFFLINE":
                title = "司机离线";
                content = "司机离线" + (remark != null ? "：" + remark : "");
                severity = 1;
                simulationEngine.setDriverOnline(vehicleId, false);
                break;
            case "CONGESTION":
                title = "道路拥堵";
                content = "道路拥堵" + (remark != null ? "：" + remark : "");
                severity = 1;
                simulationEngine.setTrafficFactor(vehicleId, 1.5); // 真正增加旅行时间
                break;
            case "DELAY":
                title = "临时晚点";
                content = "车辆临时晚点" + (remark != null ? "：" + remark : "");
                severity = 1;
                simulationEngine.setDelaySeconds(vehicleId, durationSeconds != null ? durationSeconds : 300);
                break;
            case "ORDER_CANCEL":
                title = "取消订单";
                content = "取消订单" + (remark != null ? "：" + remark : "");
                severity = 0;
                break;
            case "ORDER_ADD":
                title = "新增订单";
                content = "新增临时订单" + (remark != null ? "：" + remark : "");
                severity = 0;
                break;
            case "FAULT_RECOVER":
                title = "故障恢复";
                content = "车辆故障已恢复" + (remark != null ? "：" + remark : "");
                severity = 0;
                simulationEngine.setVehicleFault(vehicleId, false);
                break;
            case "GPS_RECOVER":
                title = "GPS 恢复";
                content = "GPS 信号已恢复" + (remark != null ? "：" + remark : "");
                severity = 0;
                simulationEngine.setGpsAvailable(vehicleId, true);
                break;
            case "DRIVER_ONLINE":
                title = "司机上线";
                content = "司机已上线" + (remark != null ? "：" + remark : "");
                severity = 0;
                simulationEngine.setDriverOnline(vehicleId, true);
                break;
            case "CONGESTION_CLEAR":
                title = "拥堵解除";
                content = "道路拥堵已解除" + (remark != null ? "：" + remark : "");
                severity = 0;
                simulationEngine.setTrafficFactor(vehicleId, 1.0);
                break;
            default:
                title = "未知事件";
                content = "未知事件类型: " + eventType;
                severity = 0;
        }

        if (durationSeconds != null) {
            content += "（持续 " + durationSeconds + " 秒）";
        }

        recordEvent(runId, vehicleId, eventType, severity, title, content,
                simSeconds, null, stationName, lon, lat);
    }

    /**
     * 获取模拟统计数据
     */
    public SimulationRuntimeRespVO getStatistics(Long vehicleId) {
        // 复用 getRuntime 获取完整状态
        return getRuntime(vehicleId);
    }

    // ==================== 辅助方法 ====================

    private SimulationRunDO getRunByPlanAndVehicle(Long planId, Long vehicleId) {
        return runMapper.selectOne(
                new LambdaQueryWrapperX<SimulationRunDO>()
                        .eq(SimulationRunDO::getPlanId, planId)
                        .eq(SimulationRunDO::getVehicleId, vehicleId)
                        .orderByDesc(SimulationRunDO::getId)
                        .last("LIMIT 1"));
    }

    private String runStatusName(int status) {
        return switch (status) {
            case 0 -> "已创建";
            case 1 -> "运行中";
            case 2 -> "已暂停";
            case 3 -> "已完成";
            case 4 -> "已终止";
            default -> "未知";
        };
    }
}
