package cn.iocoder.yudao.module.transport.service.simulation;

import cn.iocoder.yudao.module.transport.enums.dispatch.TaskItemStatusEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 模拟运营引擎（SimulationEngine）。
 *
 * 输入：一个调度方案（任务段）的车辆有序经停 + 每段的真实道路 polyline（RoadSegments）。
 * 模拟车辆沿真实道路 polyline 移动（不是站点间直线），时间按倍速推进：
 * 真实 60 秒 × 60x = 模拟 1 秒；模拟时间直接影响任务段状态推进（plannedArrival 触发到站/作业/完成）。
 *
 * 状态机：STOPPED / RUNNING / PAUSED / COMPLETED，并在轨迹推进中派生 ARRIVING（进入到站阈值）/
 * ARRIVED（停靠作业中）。
 *
 * 控制：Start / Pause / Resume / Reset / Set Speed。simulationEnabled=false 时所有控制为 no-op。
 * REAL GPS 优先：监控侧有司机实时上报时，即使模拟在跑也以 REAL 为准。
 */
@Service
public class SimulationEngine {

    /** 状态常量 */
    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_PAUSED = 2;
    public static final int STATUS_COMPLETED = 3;

    /** 到站判定阈值（米）：进入该范围视为 ARRIVING */
    public static final double ARRIVE_THRESHOLD_METERS = 50.0;

    /** 默认倍速 */
    public static final double DEFAULT_MULTIPLIER = 10.0;

    /** 模拟是否启用（生产默认 false，经 transport.simulation.enabled 配置注入） */
    @Setter
    @Value("${transport.simulation.enabled:false}")
    private volatile boolean simulationEnabled = false;

    /** 每车一条模拟运行（key=vehicleId） */
    private final Map<Long, SimRun> runs = new ConcurrentHashMap<>();

    /** 每车一把锁，保证同一车辆的所有操作串行执行 */
    private final ConcurrentHashMap<Long, ReentrantLock> vehicleLocks = new ConcurrentHashMap<>();

    /** 获取或创建车辆锁 */
    private ReentrantLock getVehicleLock(Long vehicleId) {
        return vehicleLocks.computeIfAbsent(vehicleId, k -> new ReentrantLock());
    }

    /** 任务段内一段（到达某经停前经过的 polyline 段 + 到站用时） */
    @Getter
    public static class SimSegment {
        private final Long stationId;
        private final String stationName;
        /** 该段 polyline（GCJ-02 经纬度数组 [lon,lat]），至少 2 点 */
        private final List<double[]> polyline;
        /** 到站累计行驶用时（秒，含上一站作业+行驶） */
        private final long arrivalSimSeconds;
        /** 本站作业秒数（到站后停靠） */
        private final long serviceSeconds;
        /** 是否最后一个经停（含 RETURN 到场站） */
        private final boolean terminal;

        public SimSegment(Long stationId, String stationName, List<double[]> polyline,
                          long arrivalSimSeconds, long serviceSeconds, boolean terminal) {
            this.stationId = stationId;
            this.stationName = stationName;
            this.polyline = polyline;
            this.arrivalSimSeconds = arrivalSimSeconds;
            this.serviceSeconds = serviceSeconds;
            this.terminal = terminal;
        }

        /** 本段 polyline 长度（米，累加相邻点 Haversine） */
        public double lengthMeters() {
            double total = 0;
            for (int i = 1; i < polyline.size(); i++) {
                total += haversineMeters(polyline.get(i - 1), polyline.get(i));
            }
            return total;
        }
    }

    /** 运行时状态：场景/异常注入影响引擎行为的参数。
     *
     * 数学语义：
     * - trafficFactor: 影响旅行时间。travelDuration = baseTravelDuration * trafficFactor
     * - speedFactor: 影响模拟速度。effectiveMultiplier = multiplier * speedFactor
     * - delaySeconds: 在当前段到达前增加额外等待时间（不跳变，实际暂停推进 delaySeconds 模拟秒）
     * - gpsAvailable: 只影响 VehicleLocationProvider 输出，不影响 Engine 推进
     * - vehicleFault: 停止 Engine 推进
     * - driverOnline: 标记状态，不影响 Engine（业务语义：司机离线不自动停车）
     */
    @Getter
    @Setter
    public static class SimRuntimeState {
        /** 交通因子：>1 表示拥堵，旅行时间延长。travelDuration = baseTravelDuration * trafficFactor */
        private volatile double trafficFactor = 1.0;
        /** 速度因子：>1 加速，<1 减速。effectiveMultiplier = multiplier * speedFactor */
        private volatile double speedFactor = 1.0;
        /** 延迟剩余秒数：每 tick 消耗，消耗完前不推进模拟时间 */
        private volatile long delayRemainingSeconds = 0;
        /** GPS 是否可用（只影响 VehicleLocationProvider，不影响 Engine 推进） */
        private volatile boolean gpsAvailable = true;
        /** 车辆是否故障（true=停止 Engine 推进） */
        private volatile boolean vehicleFault = false;
        /** 司机是否在线（标记状态，不影响 Engine 推进） */
        private volatile boolean driverOnline = true;
    }

    /** 一辆车的模拟运行 */
    @Getter
    public static class SimRun {
        private final Long planId;
        private final Long vehicleId;
        private final LocalDateTime taskWindowStart;
        private final List<SimSegment> segments;
        private final long totalSimSeconds;
        private volatile int status = STATUS_STOPPED;
        private volatile double multiplier = DEFAULT_MULTIPLIER;
        private volatile LocalDateTime wallStart;      // RUNNING 时的墙钟起点
        private volatile LocalDateTime pausedSimAt;    // PAUSED/FAULT 时的模拟时刻（相对任务窗口）
        private volatile LocalDateTime lastTickAt;

        // 事件去重状态：跟踪已发射的事件避免重复
        private volatile int lastEmittedSegmentIndex = -1;
        private volatile boolean lastEmittedArrived = false;
        private volatile boolean emittedStart = false;
        private volatile boolean emittedCompleted = false;
        private volatile boolean lastFaultState = false;
        private volatile boolean lastGpsState = true;

        // 运行时状态（场景/异常注入）
        private final SimRuntimeState runtimeState = new SimRuntimeState();

        SimRun(Long planId, Long vehicleId, LocalDateTime taskWindowStart,
               List<SimSegment> segments) {
            this.planId = planId;
            this.vehicleId = vehicleId;
            this.taskWindowStart = taskWindowStart;
            this.segments = List.copyOf(segments);
            this.totalSimSeconds = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).arrivalSimSeconds;
        }

        /**
         * 当前模拟时刻（相对任务窗口的模拟秒数）。
         *
         * 数学公式：
         * effectiveMultiplier = multiplier * speedFactor / trafficFactor
         * simSeconds = wallElapsed * effectiveMultiplier
         *
         * 其中 trafficFactor 影响的是"模拟时间相对于墙钟的流逝速度"：
         * trafficFactor=1.5 → 模拟时间流逝变慢（需要更多墙钟时间才能推进相同的模拟时间）
         * 这等价于"旅行时间延长 50%"。
         *
         * delayRemainingSeconds：每 tick 消耗，消耗完前模拟时间不推进。
         */
        public long currentSimSeconds() {
            if (status == STATUS_STOPPED) {
                return 0;
            }
            if (status == STATUS_PAUSED) {
                return pausedSimAt != null ? Duration.between(taskWindowStart, pausedSimAt).getSeconds() : 0;
            }
            // 故障时暂停在故障时刻
            if (runtimeState.isVehicleFault()) {
                return pausedSimAt != null ? Duration.between(taskWindowStart, pausedSimAt).getSeconds() : 0;
            }
            if (status == STATUS_COMPLETED) {
                return totalSimSeconds;
            }
            if (wallStart == null) {
                return 0;
            }
            long wallElapsed = Duration.between(wallStart, LocalDateTime.now()).getSeconds();

            // 延迟消耗：每秒墙钟消耗 1 秒延迟
            if (runtimeState.getDelayRemainingSeconds() > 0) {
                long delayConsumed = Math.min(wallElapsed, runtimeState.getDelayRemainingSeconds());
                runtimeState.setDelayRemainingSeconds(runtimeState.getDelayRemainingSeconds() - delayConsumed);
                wallElapsed -= delayConsumed;
            }

            // 速度因子和交通因子共同影响模拟时间推进
            // effectiveMultiplier = multiplier * speedFactor / trafficFactor
            double effectiveMultiplier = multiplier * runtimeState.getSpeedFactor() / runtimeState.getTrafficFactor();
            long simSeconds = (long) Math.floor(wallElapsed * effectiveMultiplier);
            return Math.min(totalSimSeconds, simSeconds);
        }
    }

    /**
     * 模拟事件类型常量
     */
    public static final String EVENT_START = "START";
    public static final String EVENT_ROUTE_START = "ROUTE_START";
    public static final String EVENT_STATION_ARRIVE = "STATION_ARRIVE";
    public static final String EVENT_STATION_DEPART = "STATION_DEPART";
    public static final String EVENT_COMPLETED = "COMPLETED";
    public static final String EVENT_PAUSED = "PAUSED";
    public static final String EVENT_RESUMED = "RESUMED";
    public static final String EVENT_RESET = "RESET";

    /**
     * tick 产生的状态变化事件
     */
    @Getter
    public static class SimEvent {
        private final String eventType;
        private final int severity;  // 0=info, 1=warning, 2=critical
        private final String title;
        private final String content;
        private final Long stationId;
        private final String stationName;
        private final long simSeconds;

        public SimEvent(String eventType, int severity, String title, String content,
                        Long stationId, String stationName, long simSeconds) {
            this.eventType = eventType;
            this.severity = severity;
            this.title = title;
            this.content = content;
            this.stationId = stationId;
            this.stationName = stationName;
            this.simSeconds = simSeconds;
        }
    }

    /**
     * tick 并检测状态变化，返回需要发射的事件列表（幂等）。
     * 调用方负责持久化事件。
     *
     * 状态转换检测：
     * - START / ROUTE_START: 首次 tick
     * - STATION_ARRIVE: 进入到站阈值
     * - STATION_DEPART: 段索引增加
     * - COMPLETED: 最后一段完成
     * - VEHICLE_FAULT: 故障状态变化
     * - GPS_LOST / GPS_RECOVER: GPS 状态变化
     */
    public List<SimEvent> tickWithEvents(Long vehicleId) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null || !simulationEnabled || run.segments.isEmpty()) {
                return List.of();
            }

            SimRuntimeState rt = run.getRuntimeState();
            List<SimEvent> events = new ArrayList<>();
            long simSeconds = run.currentSimSeconds();

            // 检测故障状态变化（幂等）
            if (rt.isVehicleFault() && !run.lastFaultState) {
                run.lastFaultState = true;
                SimSegment currentSeg = run.segments.get(Math.min(run.lastEmittedSegmentIndex + 1, run.segments.size() - 1));
                events.add(new SimEvent("VEHICLE_FAULT", 2, "车辆故障",
                        "车辆发生故障，停止推进",
                        currentSeg.stationId, currentSeg.stationName, simSeconds));
            } else if (!rt.isVehicleFault() && run.lastFaultState) {
                run.lastFaultState = false;
                SimSegment currentSeg = run.segments.get(Math.min(run.lastEmittedSegmentIndex + 1, run.segments.size() - 1));
                events.add(new SimEvent("VEHICLE_RECOVER", 0, "故障恢复",
                        "车辆故障已恢复，继续推进",
                        currentSeg.stationId, currentSeg.stationName, simSeconds));
            }

            // 检测 GPS 状态变化（幂等）
            if (!rt.isGpsAvailable() && run.lastGpsState) {
                run.lastGpsState = false;
                events.add(new SimEvent("GPS_LOST", 1, "GPS 信号丢失",
                        "GPS 信号丢失，位置不可用",
                        null, null, simSeconds));
            } else if (rt.isGpsAvailable() && !run.lastGpsState) {
                run.lastGpsState = true;
                events.add(new SimEvent("GPS_RECOVER", 0, "GPS 恢复",
                        "GPS 信号已恢复",
                        null, null, simSeconds));
            }

            // 故障状态：不推进位置
            if (rt.isVehicleFault()) {
                return events;
            }

            // 首次 tick：发射 START + ROUTE_START
            if (!run.emittedStart) {
                run.emittedStart = true;
                SimSegment first = run.segments.get(0);
                events.add(new SimEvent(EVENT_START, 0, "模拟启动",
                        "模拟运行开始，倍速 " + run.getMultiplier() + "×",
                        first.stationId, first.stationName, 0));
                events.add(new SimEvent(EVENT_ROUTE_START, 0, "进入路线",
                        "开始沿路线行驶",
                        first.stationId, first.stationName, 0));
            }

            SimTick tick = computeTick(run, simSeconds);
            if (tick == null) {
                return events;
            }

            // 检测段变化（到站/离站）
            int currentIdx = tick.getSegmentIndex();
            boolean currentArrived = tick.isArrived();

            // 到站事件：段索引未变但从"未到站"变为"已到站"（幂等）
            if (currentArrived && !run.lastEmittedArrived) {
                SimSegment seg = run.segments.get(Math.min(currentIdx, run.segments.size() - 1));
                events.add(new SimEvent(EVENT_STATION_ARRIVE, 0, "到达 " + seg.stationName,
                        "车辆到达 " + seg.stationName,
                        seg.stationId, seg.stationName, simSeconds));
            }

            // 离站事件：段索引增加（进入下一段）（幂等）
            if (currentIdx > run.lastEmittedSegmentIndex && run.lastEmittedSegmentIndex >= 0) {
                SimSegment prevSeg = run.segments.get(Math.min(run.lastEmittedSegmentIndex, run.segments.size() - 1));
                events.add(new SimEvent(EVENT_STATION_DEPART, 0, "离开 " + prevSeg.stationName,
                        "停站完成，前往下一站",
                        prevSeg.stationId, prevSeg.stationName, simSeconds));
            }

            // 完成事件（幂等）
            if (run.status == STATUS_COMPLETED && !run.emittedCompleted) {
                run.emittedCompleted = true;
                SimSegment last = run.segments.get(run.segments.size() - 1);
                events.add(new SimEvent(EVENT_COMPLETED, 0, "模拟完成",
                        "模拟运行完成，总耗时 " + (simSeconds / 60) + " 分钟",
                        last.stationId, last.stationName, simSeconds));
            }

            // 更新去重状态
            run.lastEmittedSegmentIndex = currentIdx;
            run.lastEmittedArrived = currentArrived;

            return events;
        } finally {
            lock.unlock();
        }
    }

    // ==================== 运行时状态修改 ====================

    /** 获取运行时状态（只读，不需要锁） */
    public SimRuntimeState getRuntimeState(Long vehicleId) {
        SimRun run = runs.get(vehicleId);
        return run != null ? run.getRuntimeState() : null;
    }

    /** 设置车辆故障状态。故障时暂停模拟时间推进，恢复时从暂停点继续。 */
    public void setVehicleFault(Long vehicleId, boolean fault) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            SimRuntimeState rt = run.getRuntimeState();
            if (fault && !rt.isVehicleFault()) {
                // 进入故障：保存当前模拟时刻
                run.pausedSimAt = taskWindowStart(run).plusSeconds(run.currentSimSeconds());
            } else if (!fault && rt.isVehicleFault()) {
                // 恢复故障：从暂停点继续，重置 wallStart
                run.wallStart = LocalDateTime.now();
            }
            rt.setVehicleFault(fault);
        } finally {
            lock.unlock();
        }
    }

    /** 设置 GPS 可用状态。GPS_LOST 不影响 Engine 推进，只影响 VehicleLocationProvider 输出。 */
    public void setGpsAvailable(Long vehicleId, boolean available) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            run.getRuntimeState().setGpsAvailable(available);
        } finally {
            lock.unlock();
        }
    }

    /** 设置司机在线状态。标记状态，不影响 Engine 推进。 */
    public void setDriverOnline(Long vehicleId, boolean online) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            run.getRuntimeState().setDriverOnline(online);
        } finally {
            lock.unlock();
        }
    }

    /** 设置交通因子（拥堵）。trafficFactor=1.5 表示旅行时间延长 50%。 */
    public void setTrafficFactor(Long vehicleId, double factor) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            run.getRuntimeState().setTrafficFactor(Math.max(0.1, factor));
        } finally {
            lock.unlock();
        }
    }

    /** 设置延迟秒数。延迟期间模拟时间不推进，等价于在当前状态暂停 delay 秒墙钟时间。 */
    public void setDelaySeconds(Long vehicleId, long delay) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            run.getRuntimeState().setDelayRemainingSeconds(Math.max(0, delay));
        } finally {
            lock.unlock();
        }
    }

    /** 设置速度因子。speedFactor=2 表示模拟速度加倍。 */
    public void setSpeedFactor(Long vehicleId, double factor) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            run.getRuntimeState().setSpeedFactor(Math.max(0.1, factor));
        } finally {
            lock.unlock();
        }
    }

    /** 重置运行时状态为默认值（清除所有场景/注入效果） */
    public void resetRuntimeState(Long vehicleId) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null) return;
            SimRuntimeState rt = run.getRuntimeState();
            rt.setTrafficFactor(1.0);
            rt.setSpeedFactor(1.0);
            rt.setDelayRemainingSeconds(0);
            rt.setGpsAvailable(true);
            rt.setVehicleFault(false);
            rt.setDriverOnline(true);
        } finally {
            lock.unlock();
        }
    }

    /** 检查 GPS 是否可用（供 VehicleLocationProvider 使用，只读） */
    public boolean isGpsAvailable(Long vehicleId) {
        SimRun run = runs.get(vehicleId);
        if (run == null) return true; // 无运行时默认可用
        return run.getRuntimeState().isGpsAvailable();
    }

    private static LocalDateTime taskWindowStart(SimRun run) {
        return run.taskWindowStart;
    }

    // ==================== 控制 ====================

    /** 启动模拟运行。同一车辆已有运行时会被替换。 */
    public void start(Long planId, Long vehicleId, LocalDateTime taskWindowStart, List<SimSegment> segments,
                      double multiplier) {
        if (!simulationEnabled || planId == null || vehicleId == null || segments == null || segments.isEmpty()) {
            return;
        }
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = new SimRun(planId, vehicleId, taskWindowStart, segments);
            run.multiplier = multiplier > 0 ? multiplier : DEFAULT_MULTIPLIER;
            run.status = STATUS_RUNNING;
            run.wallStart = LocalDateTime.now();
            run.lastTickAt = run.wallStart;
            runs.put(vehicleId, run);
        } finally {
            lock.unlock();
        }
    }

    public void pause(Long vehicleId) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null || !simulationEnabled) return;
            if (run.status == STATUS_RUNNING) {
                run.pausedSimAt = taskWindowStart(run).plusSeconds(run.currentSimSeconds());
                run.status = STATUS_PAUSED;
            }
        } finally {
            lock.unlock();
        }
    }

    public void resume(Long vehicleId) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null || !simulationEnabled) return;
            if (run.status == STATUS_PAUSED) {
                run.wallStart = LocalDateTime.now();
                run.status = STATUS_RUNNING;
            }
        } finally {
            lock.unlock();
        }
    }

    public void reset(Long vehicleId) {
        if (!simulationEnabled) return;
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            runs.remove(vehicleId);
        } finally {
            lock.unlock();
        }
    }

    public void setSpeed(Long vehicleId, double multiplier) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null || !simulationEnabled) return;
            // 保留当前模拟时刻作为新起点，避免改速导致跳变
            long simNow = run.currentSimSeconds();
            run.multiplier = multiplier > 0 ? multiplier : DEFAULT_MULTIPLIER;
            if (run.status == STATUS_RUNNING) {
                run.pausedSimAt = taskWindowStart(run).plusSeconds(simNow);
                run.wallStart = LocalDateTime.now();
            }
        } finally {
            lock.unlock();
        }
    }

    /** 当前模拟推进结果（位置/状态/当前段）；无运行或未启用返回 null */
    public SimTick tick(Long vehicleId) {
        ReentrantLock lock = getVehicleLock(vehicleId);
        lock.lock();
        try {
            SimRun run = runs.get(vehicleId);
            if (run == null || !simulationEnabled || run.segments.isEmpty()) return null;
            long simSeconds = run.currentSimSeconds();
            if (simSeconds >= run.totalSimSeconds && run.status == STATUS_RUNNING) {
                run.status = STATUS_COMPLETED;
            }
            return computeTick(run, simSeconds);
        } finally {
            lock.unlock();
        }
    }

    /** 获取运行实例（只读，不需要锁） */
    public SimRun getRun(Long vehicleId) {
        return runs.get(vehicleId);
    }

    // ==================== 纯位置计算（可单测） ====================

    /** 推进结果 */
    @Getter
    public static class SimTick {
        private final double longitude;
        private final double latitude;
        private final int status;            // 引擎状态
        private final int segmentIndex;      // 当前所在经停段
        private final String stationName;    // 当前段站点
        private final boolean arrived;       // 是否已到站（停靠作业中）
        private final long simSeconds;

        public SimTick(double longitude, double latitude, int status, int segmentIndex,
                       String stationName, boolean arrived, long simSeconds) {
            this.longitude = longitude;
            this.latitude = latitude;
            this.status = status;
            this.segmentIndex = segmentIndex;
            this.stationName = stationName;
            this.arrived = arrived;
            this.simSeconds = simSeconds;
        }
    }

    /**
     * 纯计算：给定任务段与模拟秒数，返回沿真实 polyline 插值后的位置与状态。
     * 逐段累计行驶秒数定位当前段，在段内 polyline 上按行驶进度线性插值。
     */
    public static SimTick computeTick(SimRun run, long simSeconds) {
        List<SimSegment> segments = run.segments;
        if (segments.isEmpty()) {
            return null;
        }
        if (simSeconds <= 0) {
            SimSegment first = segments.get(0);
            double[] p = first.polyline.isEmpty() ? new double[]{0, 0} : first.polyline.get(0);
            return new SimTick(p[0], p[1], STATUS_RUNNING, 0, first.stationName, false, simSeconds);
        }
        // 找到当前行驶段：已驶离其站的段数（到达+作业 ≤ simSeconds 即已驶离），+1 即当前驶向的段
        int idx = 0;
        for (int i = 0; i < segments.size(); i++) {
            long departTime = segments.get(i).arrivalSimSeconds + segments.get(i).serviceSeconds;
            if (departTime <= simSeconds) {
                idx = i + 1;
            } else {
                break;
            }
        }
        idx = Math.min(idx, segments.size() - 1);
        SimSegment seg = segments.get(idx);
        // 段内行驶起点 = 上一站驶离时刻（首段为 0）
        long segStart = idx == 0 ? 0 : segments.get(idx - 1).arrivalSimSeconds + segments.get(idx - 1).serviceSeconds;
        // 已到站且未过作业期 → ARRIVED（停靠）；末段(RETURN)到达即完成停靠
        long serviceEnd = seg.arrivalSimSeconds + seg.serviceSeconds;
        if (simSeconds >= seg.arrivalSimSeconds) {
            double[] p = seg.polyline.isEmpty() ? new double[]{0, 0} : seg.polyline.get(seg.polyline.size() - 1);
            boolean arrived = simSeconds < serviceEnd || (seg.terminal && idx == segments.size() - 1);
            return new SimTick(p[0], p[1], STATUS_RUNNING, idx, seg.stationName, arrived, simSeconds);
        }
        // 段内行驶：按到站秒内的进度在 polyline 上插值
        long travelSeconds = seg.arrivalSimSeconds - segStart;
        double progress = travelSeconds > 0 ? (double) (simSeconds - segStart) / travelSeconds : 0;
        progress = Math.max(0, Math.min(1, progress));
        double[] pos = interpolatePolyline(seg.polyline, progress);
        return new SimTick(pos[0], pos[1], STATUS_RUNNING, idx, seg.stationName, false, simSeconds);
    }

    /** 在 polyline 上按里程比例插值 */
    public static double[] interpolatePolyline(List<double[]> polyline, double progress) {
        if (polyline == null || polyline.isEmpty()) {
            return new double[]{0, 0};
        }
        if (polyline.size() == 1 || progress <= 0) {
            return polyline.get(0);
        }
        if (progress >= 1) {
            return polyline.get(polyline.size() - 1);
        }
        List<Double> cum = new ArrayList<>();
        double total = 0;
        for (int i = 1; i < polyline.size(); i++) {
            total += haversineMeters(polyline.get(i - 1), polyline.get(i));
            cum.add(total);
        }
        double target = progress * total;
        for (int i = 0; i < cum.size(); i++) {
            if (target <= cum.get(i)) {
                double[] a = polyline.get(i);
                double[] b = polyline.get(i + 1);
                double segLen = cum.get(i) - (i > 0 ? cum.get(i - 1) : 0);
                double t = segLen > 0 ? (target - (i > 0 ? cum.get(i - 1) : 0)) / segLen : 0;
                return new double[]{
                        a[0] + (b[0] - a[0]) * t,
                        a[1] + (b[1] - a[1]) * t
                };
            }
        }
        return polyline.get(polyline.size() - 1);
    }

    /** 两点 Haversine 米 */
    public static double haversineMeters(double[] a, double[] b) {
        return haversineMeters(a[0], a[1], b[0], b[1]);
    }

    public static double haversineMeters(double lon1, double lat1, double lon2, double lat2) {
        double rlat1 = Math.toRadians(lat1);
        double rlat2 = Math.toRadians(lat2);
        double dlat = Math.toRadians(lat2 - lat1);
        double dlon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dlat / 2) * Math.sin(dlat / 2)
                + Math.cos(rlat1) * Math.cos(rlat2) * Math.sin(dlon / 2) * Math.sin(dlon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(h));
    }

    /** 辅助：把任务段明细状态映射为模拟状态（供监控复用）；引擎内部用状态机 */
    public static String itemStatusName(int engineStatus, boolean arrived) {
        if (engineStatus == STATUS_STOPPED) {
            return TaskItemStatusEnum.PENDING.nameOf(TaskItemStatusEnum.PENDING.getStatus());
        }
        if (arrived) {
            return TaskItemStatusEnum.ARRIVED.nameOf(TaskItemStatusEnum.ARRIVED.getStatus());
        }
        return TaskItemStatusEnum.EN_ROUTE.nameOf(TaskItemStatusEnum.EN_ROUTE.getStatus());
    }

    /** 取当前所在经停编号（供任务段状态推进） */
    public static Long currentSegmentStationId(SimRun run, long simSeconds) {
        SimTick tick = computeTick(run, simSeconds);
        if (tick == null || run.segments.isEmpty()) {
            return null;
        }
        return run.segments.get(Math.min(tick.getSegmentIndex(), run.segments.size() - 1)).stationId;
    }
}
