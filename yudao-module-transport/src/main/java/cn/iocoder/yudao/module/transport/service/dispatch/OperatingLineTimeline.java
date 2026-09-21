package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运营线路时间轴（OperatingLineTimeline）：把「一条线路 + 一个班次 + 一个时刻」
 * 换算成"这台车此刻在线路的哪个位置、这一班还会依次经过哪些站"。
 *
 * <p><b>为什么需要：</b>公交/大巴的本职是按线路跑、每站都停，闲置运力只是**顺路**带货。
 * 所以派单必须先知道"这个站车开过没有"——车已经开过的站，绝不能再派它掉头回去取货。
 * 本类就是这条规则的唯一事实来源：
 * 班次窗口 = 一个往返，单程时长 = 窗口一半，站序计划分钟按单程时长等比缩放。</p>
 *
 * <p>纯函数（无 Spring 依赖），便于单测与回归。</p>
 */
public final class OperatingLineTimeline {

    /** 班次时长缺省值（分钟） */
    public static final int DEFAULT_DURATION_MINUTES = 60;

    private OperatingLineTimeline() {
    }

    /** 选取当前班次：优先在途窗口，其次下一班待发，否则当天最后一班（已收车） */
    public static ShiftDO selectCurrentShift(List<ShiftDO> shifts, LocalTime now) {
        if (shifts == null || shifts.isEmpty()) {
            return null;
        }
        List<ShiftDO> sorted = shifts.stream()
                .sorted(Comparator.comparing(ShiftDO::getPlannedDepartureTime))
                .toList();
        ShiftDO next = null;
        for (ShiftDO shift : sorted) {
            long elapsed = elapsedMinutes(shift, now);
            if (elapsed >= 0 && elapsed <= durationMinutes(shift)) {
                return shift;
            }
            if (elapsed < 0 && next == null) {
                next = shift;
            }
        }
        return next != null ? next : sorted.get(sorted.size() - 1);
    }

    /** 已行驶分钟（未发车为负） */
    static long elapsedMinutes(ShiftDO shift, LocalTime now) {
        if (shift.getPlannedDepartureTime() == null) {
            return -1;
        }
        return Duration.between(shift.getPlannedDepartureTime(), now).toMinutes();
    }

    static int durationMinutes(ShiftDO shift) {
        return shift.getPlannedDurationMinutes() != null ? shift.getPlannedDurationMinutes() : DEFAULT_DURATION_MINUTES;
    }

    /** 车辆在班次内的方向 */
    public enum Direction {
        /** 去程（起点 → 终点）；未发车也按去程处理（还没开过任何站） */
        FORWARD,
        /** 返程（终点 → 起点） */
        RETURNING,
        /** 本班次（一个往返）已跑完，不再接单 */
        FINISHED
    }

    /**
     * 车辆位置与剩余行程。
     *
     * @param direction       当前方向
     * @param currentStationId 当前所在站（未发车 = 起点站）
     * @param travelOrder     沿**当前行驶方向**，这一趟还会依次经过的站（去程=还没到的站；返程=还要往回开的站）
     * @param passedStations  沿当前行驶方向已经开过的站（本趟不会再经过；掉头取货检查用）
     */
    public record Position(Direction direction, Long currentStationId,
                           List<Long> travelOrder, List<Long> passedStations) {

        public boolean finished() {
            return direction == Direction.FINISHED;
        }

        /** 本班次还会经过的站点集合（判断"取货站有没有被开过"用） */
        public Set<Long> travelOrderSet() {
            return new LinkedHashSet<>(travelOrder);
        }
    }

    /** 线路站点 → 有效站序（去空编号，按 sequenceNo 升序；序号缺失排最后） */
    public static List<RouteStationDO> orderedStations(List<RouteStationDO> routeStations) {
        if (routeStations == null || routeStations.isEmpty()) {
            return List.of();
        }
        return routeStations.stream()
                .filter(rs -> rs != null && rs.getStationId() != null)
                .sorted(Comparator.comparing(RouteStationDO::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 计算车辆在 {@code at} 时刻的位置。
     *
     * @param routeStations 该线路的站点（未排序也可）
     * @param shift         该车当前执行的班次（发车时间 + 计划时长）
     * @param at            时刻（调度场景传"任务窗口开始时间"）
     * @return {@code null} 表示无法计算（无有效站点 / 无班次 / 班次无发车时间）
     */
    public static Position at(List<RouteStationDO> routeStations, ShiftDO shift, LocalTime at) {
        List<RouteStationDO> ordered = orderedStations(routeStations);
        if (ordered.isEmpty() || shift == null || shift.getPlannedDepartureTime() == null || at == null) {
            return null;
        }
        List<Long> ids = ordered.stream().map(RouteStationDO::getStationId).toList();
        int n = ids.size();

        // 班次窗口 = 一个往返：单程时长 = 窗口一半
        int duration = shift.getPlannedDurationMinutes() != null && shift.getPlannedDurationMinutes() > 0
                ? shift.getPlannedDurationMinutes() : DEFAULT_DURATION_MINUTES;
        int trip = Math.max(1, duration / 2);
        long elapsed = Duration.between(shift.getPlannedDepartureTime(), at).toMinutes();

        // 站序计划分钟 → 单程时间轴（缺失沿用上一站），保证计划分钟表与班次时长不匹配时也能定位
        int[] minutes = new int[n];
        int prev = 0;
        for (int i = 0; i < n; i++) {
            Integer planned = ordered.get(i).getPlannedMinutes();
            if (planned != null) {
                prev = planned;
            }
            minutes[i] = prev;
        }
        double scale = (double) trip / Math.max(1, minutes[n - 1]);
        for (int i = 0; i < n; i++) {
            minutes[i] = Math.max(0, (int) Math.round(minutes[i] * scale));
        }

        // 本趟（去程或返程）都跑完：收车，本次派单不再接单
        if (elapsed > 2L * trip) {
            return new Position(Direction.FINISHED, ids.get(0), List.of(), List.copyOf(ids));
        }

        // 尚未发车：整条去程都在前方（起点站也还没开过）
        if (elapsed < 0) {
            return new Position(Direction.FORWARD, ids.get(0), List.copyOf(ids), List.of());
        }

        if (elapsed > trip) {
            // 返程：从"最后一个计划分钟 <= phase 的站"往回开
            long phase = 2L * trip - elapsed;
            int idx = 0;
            for (int i = 0; i < n; i++) {
                if (minutes[i] <= phase) {
                    idx = i;
                }
            }
            List<Long> travel = new ArrayList<>();
            for (int i = idx; i >= 0; i--) {
                travel.add(ids.get(i));
            }
            List<Long> passed = new ArrayList<>();
            for (int i = idx + 1; i < n; i++) {
                passed.add(ids.get(i));
            }
            return new Position(Direction.RETURNING, ids.get(idx), travel, passed);
        }

        // 去程：当前/下一站 = 第一个计划分钟 >= elapsed 的站；它之后的站都还没到
        int idx = n - 1;
        for (int i = 0; i < n; i++) {
            if (minutes[i] >= elapsed) {
                idx = i;
                break;
            }
        }
        List<Long> travel = new ArrayList<>();
        for (int i = idx; i < n; i++) {
            travel.add(ids.get(i));
        }
        List<Long> passed = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            passed.add(ids.get(i));
        }
        return new Position(Direction.FORWARD, ids.get(idx), travel, passed);
    }

    /**
     * 任务窗口内这台车会**依次经过**的站（= 本班次骨架的候选）。
     *
     * <p>与 {@link #at} 的区别：{@code at} 给的是"从此刻到本班次收车的全部行程"，
     * 这里再按窗口结束时刻截断——窗口外才经过的站不该出现在这次派单的骨架里，
     * 否则算法会拿"整条线跑完的时间"去撞窗口上限，白白判成排不进时间窗。</p>
     *
     * @return 窗口内依次经过的站（可能为空 = 窗口内不跑车）；无法计算时返回 {@code null}
     */
    public static List<Long> windowStations(List<RouteStationDO> routeStations, ShiftDO shift,
                                            LocalTime windowStart, LocalTime windowEnd) {
        Position start = at(routeStations, shift, windowStart);
        if (start == null) {
            return null;
        }
        if (start.finished() || start.travelOrder().isEmpty()) {
            return List.of();
        }
        List<Long> travelStart = start.travelOrder();
        Position end = windowEnd == null ? null : at(routeStations, shift, windowEnd);
        // 两个时刻的"剩余行程"是同一趟往返序列的两段后缀 → 窗口内行程 = 长后缀去掉短后缀
        int keep = end == null ? travelStart.size() : travelStart.size() - end.travelOrder().size();
        if (keep <= 0) {
            return List.of();
        }
        return List.copyOf(travelStart.subList(0, Math.min(keep, travelStart.size())));
    }
}
