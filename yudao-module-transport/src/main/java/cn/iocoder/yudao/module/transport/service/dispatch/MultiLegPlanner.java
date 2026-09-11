package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.DispatchPlanningModeEnum;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 多段联运规划器（MultiLegPlanner）：判断直达 / 2 段 / 3 段，并按"综合成本最低"给出推荐 + 解释。
 *
 * 设计要点（对应需求 §41~§46、§102~§103、§136）：
 * 1. 直达优先：直达可行时，除非联运明显更快（超过 {@link #DIRECT_PREFER_MARGIN_MINUTES} 分钟），否则选直达；
 * 2. 不为了演示强制联运，也不为了演示强制直达——完全由评分决定；
 * 3. 换乘站候选 = "多线路交汇"（本段线路能到、下一段线路能走）+ 车辆可达 + 开放调度，
 *    而不是几何上最近的点；
 * 4. 评分 score = 总时长 + 换乘次数×换乘惩罚 + 绕行公里×绕行惩罚（越小越优）；
 * 5. 输出候选方案列表 + 推荐理由，供后台"调度结果解释"直接展示。
 *
 * 说明：本规划器不取代原有 OR-Tools 调度算法——它负责"运输段划分"，段内车辆/司机分配仍由现有调度与
 * {@link MultiLegService} 完成。
 */
@Service
public class MultiLegPlanner {

    /** 乡村班线平均时速(km/h) */
    static final double AVG_SPEED_KMH = GeoDistanceUtil.DEFAULT_AVG_SPEED_KMH;
    /** 每次换乘惩罚(分钟)：换乘需要等待与装卸，等效时间成本 */
    static final int TRANSFER_PENALTY_MINUTES = 15;
    /** 绕行惩罚(分钟/km)：绕行带来的额外成本 */
    static final double DETOUR_PENALTY_MINUTES_PER_KM = 2.0;
    /** 换乘站作业停留(分钟) */
    static final int HANDOVER_DWELL_MINUTES = 20;
    /** 首段准备时间(分钟) */
    static final int PREPARE_MINUTES = 10;
    /** 直达可接受最大里程(km)：超过视为"直达不合理" */
    static final double MAX_DIRECT_KM = 60.0;
    /** 直达优先容差(分钟)：联运比直达快不超过该值仍选直达（避免为省几分钟强制换乘） */
    static final int DIRECT_PREFER_MARGIN_MINUTES = 10;
    /**
     * 换乘方案可接受绕行比例 / 保底(km)。
     * 说明：公交线网是"固定线路 + 换乘"而非直线，真实换乘必然绕行（如 邮电大学→磁器街 需经
     * 南坪站、五公里两次换乘）；这里的阈值按公交线网实测校准，过紧会把真实可行方案误判为"不可行"。
     */
    static final double TWO_LEG_DETOUR_RATIO = 0.8;
    static final double TWO_LEG_MIN_DETOUR_KM = 4.0;
    static final double THREE_LEG_DETOUR_RATIO = 1.6;
    static final double THREE_LEG_MIN_DETOUR_KM = 8.0;
    /**
     * 换乘枢纽候选数上限：真实公交线网里换乘点往往不在"最近几个站"里（如 邮电大学→磁器街 需经
     * 南坪站、五公里），上限过小会漏掉可行链路。170 站量级下 O(n²) 组合仅 3 万次，开销可忽略。
     */
    static final int THREE_LEG_HUB_LIMIT = 200;

    /** 运输段草案 */
    public record LegDraft(int sequence, Long fromStationId, Long toStationId,
                           double distanceKm, int durationMinutes, boolean handoverRequired) {
    }

    /** 候选方案（供后台对比展示） */
    public record Candidate(String mode, String modeName, int legCount, int transferCount,
                            double distanceKm, int durationMinutes, double score,
                            List<LegDraft> legs, Long transferStationId, String reason) {
    }

    /** 规划结果（推荐方案 + 全部候选） */
    public record PlanResult(String mode, int legCount, int transferCount, double distanceKm,
                             int durationMinutes, double score, List<LegDraft> legs,
                             List<Candidate> candidates, Long transferStationId, String reason) {

        public boolean isMultiLeg() {
            return DispatchPlanningModeEnum.MULTI_LEG.getMode().equals(mode) && transferCount > 0;
        }
    }

    /**
     * 规划订单运输段。
     *
     * @param order        运输订单
     * @param pickup       取货站
     * @param delivery     送达站
     * @param stations     全部启用站点（含可达性配置）
     * @param routeStations 全部线路站点关系（用于判断"多线路交汇"）
     */
    public PlanResult plan(TransportOrderDO order, StationDO pickup, StationDO delivery,
                          List<StationDO> stations, List<RouteStationDO> routeStations) {
        if (pickup == null || delivery == null
                || pickup.getLongitude() == null || pickup.getLatitude() == null
                || delivery.getLongitude() == null || delivery.getLatitude() == null) {
            throw new IllegalArgumentException("取/送站缺少坐标，无法规划运输段");
        }
        Set<Long> stationsOfRoute;
        RouteIndex index = new RouteIndex(routeStations);
        double directKm = distance(pickup, delivery);
        List<Candidate> candidates = new ArrayList<>();

        // 1) 直达候选：同一条线路覆盖取/送站，且里程不超过直达上限
        boolean directFeasible = index.sameRoute(pickup.getId(), delivery.getId())
                && directKm <= MAX_DIRECT_KM;
        if (directFeasible) {
            candidates.add(directCandidate(pickup, delivery, directKm));
        }

        // 2) 两段联运：换乘站须"取货线路能到 + 送达线路能走"
        Candidate twoLeg = bestTwoLeg(pickup, delivery, stations, index, directKm);
        if (twoLeg != null) {
            candidates.add(twoLeg);
        }
        // 3) 三段联运：两跳枢纽链（起点→换乘1→换乘2→终点）
        Candidate threeLeg = bestThreeLeg(pickup, delivery, stations, index, directKm,
                twoLeg == null ? null : twoLeg.transferStationId());
        if (threeLeg != null) {
            candidates.add(threeLeg);
        }

        // 4) 兜底：既无直达线路也无可用换乘站（数据不全）→ 按取送站直达兜底，保证可调度
        if (candidates.isEmpty()) {
            Candidate fallback = new Candidate(DispatchPlanningModeEnum.DIRECT.getMode(),
                    DispatchPlanningModeEnum.DIRECT.getName(), 1, 0, round2(directKm),
                    durationOf(List.of(directKm)), score(1, 0, 0, directKm),
                    List.of(new LegDraft(1, pickup.getId(), delivery.getId(), round2(directKm),
                            travelMinutes(directKm), false)),
                    null, "暂无可用线路/换乘站数据，按取送站直达兜底");
            candidates.add(fallback);
        }

        // 5) 选择：评分最小；若直达与联运差距在容差内 → 直达优先
        Candidate byScore = candidates.stream()
                .min(Comparator.comparingDouble(Candidate::score)
                        .thenComparingInt(Candidate::legCount))
                .orElseThrow();
        Candidate direct = candidates.stream()
                .filter(c -> DispatchPlanningModeEnum.DIRECT.getMode().equals(c.mode()))
                .findFirst().orElse(null);
        Candidate chosen = byScore;
        if (direct != null && byScore != direct
                && direct.durationMinutes() - byScore.durationMinutes() <= DIRECT_PREFER_MARGIN_MINUTES) {
            chosen = direct;
        }
        String reason = explain(chosen, candidates, pickup, delivery);
        return new PlanResult(chosen.mode(), chosen.legCount(), chosen.transferCount(),
                chosen.distanceKm(), chosen.durationMinutes(), chosen.score(), chosen.legs(),
                candidates, chosen.transferStationId(), reason);
    }

    private Candidate directCandidate(StationDO pickup, StationDO delivery, double directKm) {
        return new Candidate(DispatchPlanningModeEnum.DIRECT.getMode(), DispatchPlanningModeEnum.DIRECT.getName(),
                1, 0, round2(directKm), durationOf(List.of(directKm)), score(1, 0, 0, directKm),
                List.of(new LegDraft(1, pickup.getId(), delivery.getId(), round2(directKm),
                        travelMinutes(directKm), false)),
                null, "同一条线路直达，无需换乘");
    }

    private Candidate bestTwoLeg(StationDO pickup, StationDO delivery, List<StationDO> stations,
                                 RouteIndex index, double directKm) {
        double tolerance = Math.max(directKm * TWO_LEG_DETOUR_RATIO, TWO_LEG_MIN_DETOUR_KM);
        Candidate best = null;
        for (StationDO hub : hubs(pickup, delivery, stations)) {
            if (!index.sameRoute(pickup.getId(), hub.getId())
                    || !index.sameRoute(hub.getId(), delivery.getId())) {
                continue; // 换乘站必须多线路交汇（取货线路能到、送达线路能走）
            }
            double d1 = distance(pickup, hub);
            double d2 = distance(hub, delivery);
            double detour = d1 + d2 - directKm;
            if (detour > tolerance) {
                continue;
            }
            int duration = durationOf(List.of(d1, d2));
            double sc = score(2, 1, detour, d1 + d2);
            if (best == null || sc < best.score()) {
                List<LegDraft> legs = List.of(
                        new LegDraft(1, pickup.getId(), hub.getId(), round2(d1), travelMinutes(d1), true),
                        new LegDraft(2, hub.getId(), delivery.getId(), round2(d2), travelMinutes(d2), false));
                best = new Candidate(DispatchPlanningModeEnum.MULTI_LEG.getMode(),
                        DispatchPlanningModeEnum.MULTI_LEG.getName(), 2, 1, round2(d1 + d2),
                        duration, sc, legs, hub.getId(),
                        "经换乘站「" + hub.getStationName() + "」两段联运");
            }
        }
        return best;
    }

    private Candidate bestThreeLeg(StationDO pickup, StationDO delivery, List<StationDO> stations,
                                   RouteIndex index, double directKm, Long preferredHub) {
        double tolerance = Math.max(directKm * THREE_LEG_DETOUR_RATIO, THREE_LEG_MIN_DETOUR_KM);
        List<StationDO> hubs = hubs(pickup, delivery, stations).stream()
                .sorted(Comparator.comparingDouble((StationDO s) ->
                        distance(pickup, s) + distance(s, delivery)).thenComparing(StationDO::getId))
                .limit(THREE_LEG_HUB_LIMIT)
                .toList();
        Candidate best = null;
        for (StationDO h1 : hubs) {
            if (!index.sameRoute(pickup.getId(), h1.getId())) {
                continue;
            }
            for (StationDO h2 : hubs) {
                if (Objects.equals(h1.getId(), h2.getId())) {
                    continue;
                }
                if (!index.sameRoute(h1.getId(), h2.getId()) || !index.sameRoute(h2.getId(), delivery.getId())) {
                    continue;
                }
                double d1 = distance(pickup, h1);
                double d2 = distance(h1, h2);
                double d3 = distance(h2, delivery);
                double detour = d1 + d2 + d3 - directKm;
                if (detour > tolerance) {
                    continue;
                }
                int duration = durationOf(List.of(d1, d2, d3));
                double sc = score(3, 2, detour, d1 + d2 + d3);
                if (best == null || sc < best.score()) {
                    List<LegDraft> legs = List.of(
                            new LegDraft(1, pickup.getId(), h1.getId(), round2(d1), travelMinutes(d1), true),
                            new LegDraft(2, h1.getId(), h2.getId(), round2(d2), travelMinutes(d2), true),
                            new LegDraft(3, h2.getId(), delivery.getId(), round2(d3), travelMinutes(d3), false));
                    best = new Candidate(DispatchPlanningModeEnum.MULTI_LEG.getMode(),
                            DispatchPlanningModeEnum.MULTI_LEG.getName(), 3, 2,
                            round2(d1 + d2 + d3), duration, sc, legs, h1.getId(),
                            "经「" + h1.getStationName() + "」「" + h2.getStationName() + "」三段联运");
                }
            }
        }
        return best;
    }

    /** 换乘站候选：车辆可达 + 开放调度 + 非取送站 */
    private List<StationDO> hubs(StationDO pickup, StationDO delivery, List<StationDO> stations) {
        return stations.stream()
                .filter(s -> s.getId() != null && s.getLongitude() != null && s.getLatitude() != null)
                .filter(StationAccessUtil::dispatchEnabled)
                .filter(StationAccessUtil::vehicleAccessible)
                .filter(s -> !Objects.equals(s.getId(), pickup.getId())
                        && !Objects.equals(s.getId(), delivery.getId()))
                .toList();
    }

    /** 方案解释（后台"调度结果解释"直接用这段文案） */
    private String explain(Candidate chosen, List<Candidate> candidates, StationDO pickup, StationDO delivery) {
        Candidate direct = candidates.stream()
                .filter(c -> DispatchPlanningModeEnum.DIRECT.getMode().equals(c.mode()))
                .findFirst().orElse(null);
        if (DispatchPlanningModeEnum.DIRECT.getMode().equals(chosen.mode())) {
            if (direct == null) {
                return "按取送站直达（暂无线路/换乘站数据），预计 " + chosen.durationMinutes() + " 分钟";
            }
            Candidate multi = candidates.stream()
                    .filter(c -> c.transferCount() > 0)
                    .min(Comparator.comparingDouble(Candidate::score))
                    .orElse(null);
            if (multi == null) {
                return "推荐一段直达：直达预计 " + chosen.durationMinutes() + " 分钟，无可用换乘方案，无需换乘";
            }
            return "推荐一段直达：直达预计 " + direct.durationMinutes() + " 分钟，联运预计 "
                    + multi.durationMinutes() + " 分钟，差距不足 " + DIRECT_PREFER_MARGIN_MINUTES
                    + " 分钟，优先直达避免换乘";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("推荐").append(chosen.legCount()).append("段联运：");
        if (direct != null) {
            int save = direct.durationMinutes() - chosen.durationMinutes();
            sb.append("直达预计 ").append(direct.durationMinutes()).append(" 分钟，联运预计 ")
                    .append(chosen.durationMinutes()).append(" 分钟");
            if (save > 0) {
                sb.append("，预计节省 ").append(save).append(" 分钟");
            } else {
                sb.append("，直达线路不覆盖或里程过长（").append(round1(distance(pickup, delivery))).append("km）");
            }
        } else {
            sb.append("无线路同时覆盖取/送站，直达不可行");
        }
        sb.append("；换乘 ").append(chosen.transferCount()).append(" 次，总里程 ")
                .append(round1(chosen.distanceKm())).append("km");
        return sb.toString();
    }

    // ==================== 计算辅助 ====================

    static double score(int legCount, int transferCount, double detourKm, double totalKm) {
        double travel = totalKm / AVG_SPEED_KMH * 60;
        return travel + PREPARE_MINUTES + (legCount - 1) * HANDOVER_DWELL_MINUTES
                + transferCount * TRANSFER_PENALTY_MINUTES + Math.max(0, detourKm) * DETOUR_PENALTY_MINUTES_PER_KM;
    }

    static int travelMinutes(double km) {
        return Math.max(1, (int) Math.round(km / AVG_SPEED_KMH * 60));
    }

    static int durationOf(List<Double> legs) {
        double km = legs.stream().mapToDouble(Double::doubleValue).sum();
        return PREPARE_MINUTES + travelMinutes(km) + (legs.size() - 1) * HANDOVER_DWELL_MINUTES;
    }

    static double distance(StationDO a, StationDO b) {
        return GeoDistanceUtil.haversineKm(a.getLongitude().doubleValue(), a.getLatitude().doubleValue(),
                b.getLongitude().doubleValue(), b.getLatitude().doubleValue());
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 线路覆盖索引：stationId → 所属线路集合；用于"多线路交汇"判断 */
    private static final class RouteIndex {
        private final java.util.Map<Long, Set<Long>> byStation;

        RouteIndex(List<RouteStationDO> routeStations) {
            java.util.Map<Long, Set<Long>> map = new java.util.HashMap<>();
            for (RouteStationDO rs : routeStations) {
                if (rs.getStationId() == null || rs.getRouteId() == null) {
                    continue;
                }
                map.computeIfAbsent(rs.getStationId(), k -> new HashSet<>()).add(rs.getRouteId());
            }
            this.byStation = map;
        }

        /** 是否存在同一条线路同时覆盖两个站点 */
        boolean sameRoute(Long a, Long b) {
            Set<Long> ra = byStation.get(a);
            if (ra == null || ra.isEmpty()) {
                return false;
            }
            Set<Long> rb = byStation.get(b);
            if (rb == null || rb.isEmpty()) {
                return false;
            }
            return ra.stream().anyMatch(rb::contains);
        }
    }

}
