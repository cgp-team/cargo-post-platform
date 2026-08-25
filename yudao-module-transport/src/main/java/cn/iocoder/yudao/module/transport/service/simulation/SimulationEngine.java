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
        private volatile LocalDateTime pausedSimAt;    // PAUSED 时的模拟时刻（相对任务窗口）
        private volatile LocalDateTime lastTickAt;

        SimRun(Long planId, Long vehicleId, LocalDateTime taskWindowStart,
               List<SimSegment> segments) {
            this.planId = planId;
            this.vehicleId = vehicleId;
            this.taskWindowStart = taskWindowStart;
            this.segments = List.copyOf(segments);
            this.totalSimSeconds = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).arrivalSimSeconds;
        }

        /** 当前模拟时刻（相对任务窗口的模拟秒数） */
        public long currentSimSeconds() {
            if (status == STATUS_STOPPED) {
                return 0;
            }
            if (status == STATUS_PAUSED) {
                return pausedSimAt != null ? Duration.between(taskWindowStart, pausedSimAt).getSeconds() : 0;
            }
            if (status == STATUS_COMPLETED) {
                return totalSimSeconds;
            }
            if (wallStart == null) {
                return 0;
            }
            long wallElapsed = Duration.between(wallStart, LocalDateTime.now()).getSeconds();
            return Math.min(totalSimSeconds, (long) Math.floor(wallElapsed * multiplier));
        }
    }

    // ==================== 控制 ====================

    public void start(Long planId, Long vehicleId, LocalDateTime taskWindowStart, List<SimSegment> segments,
                      double multiplier) {
        if (!simulationEnabled || planId == null || vehicleId == null || segments == null || segments.isEmpty()) {
            return;
        }
        SimRun run = new SimRun(planId, vehicleId, taskWindowStart, segments);
        run.multiplier = multiplier > 0 ? multiplier : DEFAULT_MULTIPLIER;
        run.status = STATUS_RUNNING;
        run.wallStart = LocalDateTime.now();
        run.lastTickAt = run.wallStart;
        runs.put(vehicleId, run);
    }

    public void pause(Long vehicleId) {
        SimRun run = runs.get(vehicleId);
        if (run == null || !simulationEnabled) {
            return;
        }
        if (run.status == STATUS_RUNNING) {
            run.pausedSimAt = taskWindowStart(run).plusSeconds(run.currentSimSeconds());
            run.status = STATUS_PAUSED;
        }
    }

    public void resume(Long vehicleId) {
        SimRun run = runs.get(vehicleId);
        if (run == null || !simulationEnabled) {
            return;
        }
        if (run.status == STATUS_PAUSED) {
            run.wallStart = LocalDateTime.now();
            run.status = STATUS_RUNNING;
        }
    }

    public void reset(Long vehicleId) {
        if (!simulationEnabled) {
            return;
        }
        runs.remove(vehicleId);
    }

    public void setSpeed(Long vehicleId, double multiplier) {
        SimRun run = runs.get(vehicleId);
        if (run == null || !simulationEnabled) {
            return;
        }
        // 保留当前模拟时刻作为新起点，避免改速导致跳变
        long simNow = run.currentSimSeconds();
        run.multiplier = multiplier > 0 ? multiplier : DEFAULT_MULTIPLIER;
        if (run.status == STATUS_RUNNING) {
            run.pausedSimAt = taskWindowStart(run).plusSeconds(simNow);
            run.wallStart = LocalDateTime.now();
        }
    }

    /** 当前模拟推进结果（位置/状态/当前段）；无运行或未启用返回 null */
    public SimTick tick(Long vehicleId) {
        SimRun run = runs.get(vehicleId);
        if (run == null || !simulationEnabled || run.segments.isEmpty()) {
            return null;
        }
        long simSeconds = run.currentSimSeconds();
        if (simSeconds >= run.totalSimSeconds && run.status == STATUS_RUNNING) {
            run.status = STATUS_COMPLETED;
        }
        return computeTick(run, simSeconds);
    }

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
            double[] p = first.polyline.get(0);
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
            double[] p = seg.polyline.get(seg.polyline.size() - 1);
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

    private static LocalDateTime taskWindowStart(SimRun run) {
        return run.taskWindowStart;
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
