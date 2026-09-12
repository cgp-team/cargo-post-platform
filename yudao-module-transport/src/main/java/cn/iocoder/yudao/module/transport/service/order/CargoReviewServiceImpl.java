package cn.iocoder.yudao.module.transport.service.order;

import cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ReviewStatusEnum;
import cn.iocoder.yudao.module.transport.enums.order.ServiceModeEnum;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum.*;

/**
 * 承运审核引擎默认实现（规则式）。规则与阈值集中在此，便于评审与扩展。
 */
@Service
@Validated
public class CargoReviewServiceImpl implements CargoReviewService {

    /** 单件重量上限(kg)，超过即拒运（OVER_WEIGHT） */
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("30");

    /**
     * 承运距离上限(km)：超过即转人工确认（不入池）。
     * 业务前提：我们用的是公交/大巴**空闲运力**，车辆本职是按所属线路跑（站点全停、只能小范围绕行），
     * 过于遥远的订单（绕行代价高、成本高、时间不可控）默认不进调度池，由调度员人工判断是否接。
     */
    private static final double MAX_SERVICE_KM = 45.0;
    /** 直线距离 → 路网里程的粗略系数（审核阶段不额外调高德，避免下单链路变慢） */
    private static final double ROAD_FACTOR = 1.3;

    /** 危险品关键词（命中即拒运） */
    private static final String[] DANGEROUS_KEYWORDS = {"烟花爆竹", "烟花", "鞭炮", "爆炸", "炸药", "雷管", "汽油",
            "柴油", "酒精", "油漆", "打火机", "液化气", "甲烷"};
    /** 禁运品关键词（命中即拒运） */
    private static final String[] PROHIBITED_KEYWORDS = {"毒品", "枪支", "弹药", "管制刀具", "违禁"};
    /** 生鲜/需冷链关键词（命中转人工审核） */
    private static final String[] FRESH_KEYWORDS = {"生鲜", "冻品", "冷冻", "冷藏", "活体", "海鲜", "冰淇淋"};
    /** 大件/超规关键词（命中需客户送站或特殊安排 → CONDITIONAL） */
    private static final String[] OVERSIZE_KEYWORDS = {"大件", "家具", "家电", "冰箱", "洗衣机", "床垫", "超长", "超宽"};

    /** 场站级站点（transport_station.station_level = 1）：可直接作为客户送站交接点 */
    private static final int STATION_LEVEL_DEPOT = 1;
    /** 站点状态：启用 */
    private static final int STATUS_ENABLED = 0;

    @Resource
    private StationMapper stationMapper;
    /** 线路站序（算法可行性判定用）：能规划出 1~3 段就说明"有解决方案" */
    @Resource
    private cn.iocoder.yudao.module.transport.dal.mysql.route.RouteStationMapper routeStationMapper;
    @Resource
    private cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper routeMapper;
    @Resource
    private cn.iocoder.yudao.module.transport.service.dispatch.MultiLegPlanner multiLegPlanner;

    @Override
    public CargoReviewResult review(String goodsName, BigDecimal weightKg, Boolean freshFlag,
                                    String goodsNote, Long pickupStationId, Long deliveryStationId) {
        String name = goodsName != null ? goodsName : "";
        String note = goodsNote != null ? goodsNote : "";
        List<String> reasons = new ArrayList<>();

        // 1. 危险品/禁运品 → 拒运（最高优先级，直接返回）
        if (containsAny(name, DANGEROUS_KEYWORDS)) {
            reasons.add(DANGEROUS_GOODS.getCode());
        }
        if (containsAny(name, PROHIBITED_KEYWORDS)) {
            reasons.add(PROHIBITED_GOODS.getCode());
        }
        if (!reasons.isEmpty()) {
            return rejected(reasons);
        }

        // 2. 超重 → 拒运
        // 2. 超重 → 有解决方案（拆分为多件）→ 需客户操作，而不是直接拒运
        if (weightKg != null && weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.CONDITIONAL.getStatus())
                    .reasonCodes(List.of(OVER_WEIGHT.getCode()))
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .message(String.format("单件 %.1fkg 超过 %s kg 上限，请拆分为多件分别寄出，或联系工作人员安排大件运输",
                            weightKg.doubleValue(), MAX_WEIGHT_KG.toPlainString()))
                    .build();
        }

        // 3. 大件/超规 → 需客户送站（CONDITIONAL）：匹配"就近可交接站点"，由前端通知客户前往
        if (containsAny(name, OVERSIZE_KEYWORDS) || containsAny(note, OVERSIZE_KEYWORDS)) {
            Long servicePoint = selectServicePointStation(pickupStationId, deliveryStationId,
                    stationMapper == null ? List.of() : stationMapper.selectList());
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.CONDITIONAL.getStatus())
                    .reasonCodes(List.of(CUSTOMER_ACTION_REQUIRED.getCode()))
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.CUSTOMER_TO_STATION.getCode())
                    .servicePointStationId(servicePoint)
                    .message("大件/超规货物需到指定站点交接，请将货物送到就近服务站点")
                    .build();
        }

        // 4. 生鲜/需冷链 → 需人工审核（确认车辆冷链条件）
        if (Boolean.TRUE.equals(freshFlag) || containsAny(name, FRESH_KEYWORDS)
                || containsAny(note, FRESH_KEYWORDS)) {
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.MANUAL_REVIEW.getStatus())
                    .reasonCodes(List.of(MANUAL_REVIEW_REQUIRED.getCode()))
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .message("生鲜/冷链货物需人工确认承运条件，请等待工作人员联系")
                    .build();
        }

        // 5. 算法可行性判定（"拒运由算法自行判定"）：
        //    能规划出运输段（直达/两段/三段联运）→ 有解决方案 → 通过（并在文案里说明几段几换乘）；
        //    规划不出 → 没有解决方案 → 拒运并给出理由（当前公交线网无法覆盖该起讫点）。
        Double straightKm = stationDistanceKm(pickupStationId, deliveryStationId);
        PlanFeasibility feasibility = evaluateFeasibility(pickupStationId, deliveryStationId);
        if (!feasibility.feasible) {
            // 无解：明确告诉用户"为什么运不了"（线网覆盖不到 / 距离超出空闲运力承运范围）
            String reason = straightKm != null && straightKm * ROAD_FACTOR > MAX_SERVICE_KM
                    ? String.format("起讫点直线距离约 %.1fkm，超出公交空闲运力承运范围（约 %.0fkm 内）",
                            straightKm, MAX_SERVICE_KM / ROAD_FACTOR)
                    : "当前公交线网无法覆盖该起讫点（无直达、也无换乘联运方案）";
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.REJECTED.getStatus())
                    .reasonCodes(List.of(straightKm != null && straightKm * ROAD_FACTOR > MAX_SERVICE_KM
                            ? DETOUR_TOO_LARGE.getCode() : ROAD_UNREACHABLE.getCode()))
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .message(reason + "，无法承运")
                    .build();
        }
        if (feasibility.legCount > 1) {
            // 有解但需要联运：告诉用户会发生什么（需要换乘，时效更长），不需要用户操作
            return CargoReviewResult.builder()
                    .reviewStatus(ReviewStatusEnum.PASSED.getStatus())
                    .reasonCodes(List.of())
                    .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                    .message(String.format("审核通过：该单需 %d 段联运（%d 次换乘交接），时效会比直达长",
                            feasibility.legCount, feasibility.transferCount))
                    .build();
        }
        return CargoReviewResult.builder()
                .reviewStatus(ReviewStatusEnum.PASSED.getStatus())
                .reasonCodes(List.of())
                .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .message("审核通过，可进入待入池")
                .build();
    }

    private static CargoReviewResult rejected(List<String> reasons) {
        return CargoReviewResult.builder()
                .reviewStatus(ReviewStatusEnum.REJECTED.getStatus())
                .reasonCodes(reasons)
                .pickupServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .deliveryServiceMode(ServiceModeEnum.STATION_TO_STATION.getCode())
                .message("货物不符合运输条件，不予承运")
                .build();
    }

    /** 取货站 → 送达站的直线距离（km）；站点缺失/无坐标返回 null */
    private Double stationDistanceKm(Long pickupStationId, Long deliveryStationId) {
        if (stationMapper == null || pickupStationId == null || deliveryStationId == null) {
            return null;
        }
        var pickup = stationMapper.selectById(pickupStationId);
        var delivery = stationMapper.selectById(deliveryStationId);
        if (pickup == null || delivery == null || pickup.getLongitude() == null || pickup.getLatitude() == null
                || delivery.getLongitude() == null || delivery.getLatitude() == null) {
            return null;
        }
        return cn.iocoder.yudao.module.transport.util.GeoDistanceUtil.haversineKm(
                pickup.getLongitude().doubleValue(), pickup.getLatitude().doubleValue(),
                delivery.getLongitude().doubleValue(), delivery.getLatitude().doubleValue());
    }

    /** 可行性判定结果：能否用现有公交线网（含联运）送达 */
    private record PlanFeasibility(boolean feasible, int legCount, int transferCount) {
    }

    /**
     * 用调度用的同一套规划器判断"能不能运"：
     * 能出方案（直达/两段/三段）→ 有解决方案；出不了方案 → 没有解决方案，直接拒运并说明原因。
     * 规划失败（缺坐标/线网为空）按"不可行"处理，避免把运不了的订单放进池子里。
     */
    private PlanFeasibility evaluateFeasibility(Long pickupStationId, Long deliveryStationId) {
        if (multiLegPlanner == null || stationMapper == null || pickupStationId == null || deliveryStationId == null) {
            return new PlanFeasibility(true, 1, 0);
        }
        try {
            var pickup = stationMapper.selectById(pickupStationId);
            var delivery = stationMapper.selectById(deliveryStationId);
            if (pickup == null || delivery == null) {
                return new PlanFeasibility(false, 0, 0);
            }
            List<cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO> stations = stationMapper.selectList();
            List<cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteStationDO> routeStations =
                    routeStationMapper == null ? List.of() : routeStationMapper.selectList();
            if (routeMapper != null) {
                var enabled = new java.util.HashSet<>(routeMapper.selectEnabledDispatchRouteIds());
                routeStations = routeStations.stream().filter(rs -> enabled.contains(rs.getRouteId())).toList();
            }
            var order = cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO.builder()
                    .pickupStationId(pickupStationId).deliveryStationId(deliveryStationId).build();
            var result = multiLegPlanner.plan(order, pickup, delivery, stations, routeStations);
            if (result == null || result.legs() == null || result.legs().isEmpty()) {
                return new PlanFeasibility(false, 0, 0);
            }
            return new PlanFeasibility(true, result.legCount(), result.transferCount());
        } catch (Exception ex) {
            // 规划异常按"无解"处理：宁可让调度员人工确认，也不把不确定的订单放进调度池
            return new PlanFeasibility(false, 0, 0);
        }
    }

    private static boolean containsAny(String text, String[] keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 匹配"就近可交接站点"（客户送站时的目标站点），确定性规则：
     * 1. 取货站点本身就是启用中的场站级站点（station_level=1）→ 直接在该站交接；
     * 2. 否则在启用站点中取**距取货站点 Haversine 最近**的一个（排除取货站点本身），同距离取站点 ID 升序；
     * 3. 站点数据缺失/无候选 → 退回原逻辑（送达站点），保证流程不断。
     *
     * 说明：客户位置以自己选择的取货站点为代表（寄货页已按站点选集货），
     * 因此"就近"= 距取货站点最近的可用交接点，客户实际可步行/就近送达。
     */
    static Long selectServicePointStation(Long pickupStationId, Long deliveryStationId, List<StationDO> stations) {
        if (stations == null || stations.isEmpty()) {
            return deliveryStationId;
        }
        List<StationDO> enabled = stations.stream()
                .filter(s -> s.getId() != null && s.getLongitude() != null && s.getLatitude() != null)
                .filter(s -> s.getStatus() == null || s.getStatus() == STATUS_ENABLED)
                .toList();
        StationDO pickup = enabled.stream()
                .filter(s -> s.getId().equals(pickupStationId)).findFirst().orElse(null);
        // 1) 取货站本身就是可用场站 → 就在该站交接
        if (pickup != null && pickup.getStationLevel() != null && pickup.getStationLevel() == STATION_LEVEL_DEPOT) {
            return pickup.getId();
        }
        if (pickup == null) {
            return deliveryStationId;
        }
        // 2) 最近可用站点（排除取货站本身；若没有其他站点则退回取货站）
        return enabled.stream()
                .filter(s -> !s.getId().equals(pickupStationId))
                .min(Comparator.comparingDouble((StationDO s) -> GeoDistanceUtil.haversineKm(
                                pickup.getLongitude().doubleValue(), pickup.getLatitude().doubleValue(),
                                s.getLongitude().doubleValue(), s.getLatitude().doubleValue()))
                        .thenComparing(StationDO::getId))
                .map(StationDO::getId)
                .orElse(deliveryStationId);
    }

    /** 原因码列表 → 逗号分隔字符串（落库） */
    public static String joinReasonCodes(List<String> reasonCodes) {
        return reasonCodes == null || reasonCodes.isEmpty() ? "" : String.join(",", reasonCodes);
    }

    /** 原因码列表 → 中文文案（前端可映射；后端兜底展示） */
    public static String reasonText(List<String> reasonCodes) {
        if (reasonCodes == null || reasonCodes.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String code : reasonCodes) {
            String name = ReviewReasonCodeEnum.nameOf(code);
            if (name.isEmpty()) {
                name = code;
            }
            names.add(name);
        }
        return String.join("、", names);
    }

}
