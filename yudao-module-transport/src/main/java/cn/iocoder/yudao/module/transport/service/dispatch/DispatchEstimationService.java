package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.PricingRuleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PassengerOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.PostalOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.CargoOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PassengerOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.PostalOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.PlanItemActionEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TaskItemStatusEnum;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 调度方案估算：按经停序列估算每站预计到达时间（ETA）并回写 plan item，
 * 同时估算方案摘要（预计耗时/收入/成本）回写 plan。
 *
 * 契约约定算法服务不估算耗时与收入成本（docs/algorithm-integration.md 适配层责任 6、
 * 评审记录 Q13），故由业务后端按计价规则（transport_pricing_rule）自行估算：
 * 从批次开始时刻出发，逐站累计 Haversine 里程/均速 + 停站作业分钟。
 * 估算结果服务于小程序包裹追踪 ETA、司机端任务 ETA 与管理端方案详情。
 */
@Service
public class DispatchEstimationService {

    /** 计停站作业分钟的动作类型 */
    private static final Set<Integer> SERVICE_ACTIONS = Set.of(
            PlanItemActionEnum.BOARD.getAction(), PlanItemActionEnum.ALIGHT.getAction(),
            PlanItemActionEnum.DELIVER.getAction(), PlanItemActionEnum.PICKUP.getAction());

    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private StationMapper stationMapper;
    @Resource private TransportOrderMapper orderMapper;
    @Resource private PassengerOrderMapper passengerOrderMapper;
    @Resource private CargoOrderMapper cargoOrderMapper;
    @Resource private PostalOrderMapper postalOrderMapper;
    @Resource private PricingRuleService pricingRuleService;

    /** 路网分段（行驶秒数与真实公里，仅算法 distanceUnit=km 时给出；key = vehicleId:visitSequence） */
    public record RoadSegment(Long durationSeconds, Double distanceKm) {
    }

    /**
     * 估算并回写方案全部经停明细的预计到达时间，以及方案预计耗时/收入/成本。
     * 坐标缺失的站段按 0 里程处理（不中断后续估算）；无经停明细或出发时刻为 null 时不处理。
     *
     * @param planId     方案编号
     * @param departTime 计划出发时刻（当前口径 = 批次开始时刻）
     */
    public void estimatePlan(Long planId, LocalDateTime departTime) {
        estimatePlan(planId, departTime, Map.of());
    }

    /**
     * 同 {@link #estimatePlan(Long, LocalDateTime)}，但站间行驶优先使用算法返回的路网分段：
     * 有 {@link RoadSegment} 的站段按路网行驶秒数累计 ETA、按路网真实公里累计成本里程；
     * 缺失的站段回退直线÷均速（手工派单与 distanceUnit=degree 路径全量回退）。
     */
    public void estimatePlan(Long planId, LocalDateTime departTime, Map<String, RoadSegment> roadSegments) {
        if (planId == null || departTime == null) {
            return;
        }
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(
                new LambdaQueryWrapperX<DispatchPlanItemDO>().eq(DispatchPlanItemDO::getPlanId, planId));
        if (items.isEmpty()) {
            return;
        }
        PricingRuleDO rule = pricingRuleService.getRule();
        double speedKmh = rule.getAvgSpeedKmh() != null
                ? rule.getAvgSpeedKmh().doubleValue() : PricingRuleService.DEFAULT_AVG_SPEED_KMH;
        int serviceMinutes = rule.getStopServiceMinutes() != null
                ? rule.getStopServiceMinutes() : PricingRuleService.DEFAULT_STOP_SERVICE_MINUTES;
        // 站点坐标一次加载，消除逐站查询
        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, StationDO> stationMap = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectBatchIds(stationIds).stream()
                        .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        // 预加载订单子表（数量回写用，一次加载消除逐单查询）
        Set<Long> itemOrderIds = items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PassengerOrderDO> passengerMap = itemOrderIds.isEmpty() ? Map.of()
                : passengerOrderMapper.selectList(new LambdaQueryWrapperX<PassengerOrderDO>()
                        .in(PassengerOrderDO::getOrderId, itemOrderIds)).stream()
                        .collect(Collectors.toMap(PassengerOrderDO::getOrderId, Function.identity(), (a, b) -> a));
        Map<Long, CargoOrderDO> cargoMap = itemOrderIds.isEmpty() ? Map.of()
                : cargoOrderMapper.selectList(new LambdaQueryWrapperX<CargoOrderDO>()
                        .in(CargoOrderDO::getOrderId, itemOrderIds)).stream()
                        .collect(Collectors.toMap(CargoOrderDO::getOrderId, Function.identity(), (a, b) -> a));
        Map<Long, PostalOrderDO> postalMap = itemOrderIds.isEmpty() ? Map.of()
                : postalOrderMapper.selectList(new LambdaQueryWrapperX<PostalOrderDO>()
                        .in(PostalOrderDO::getOrderId, itemOrderIds)).stream()
                        .collect(Collectors.toMap(PostalOrderDO::getOrderId, Function.identity(), (a, b) -> a));
        // 按车分组、访问顺序升序，逐车独立累计 ETA 与里程
        Map<Long, List<DispatchPlanItemDO>> itemsByVehicle = items.stream()
                .filter(item -> item.getVehicleId() != null)
                .collect(Collectors.groupingBy(DispatchPlanItemDO::getVehicleId));
        double totalKm = 0;
        long maxDurationMinutes = 0;
        for (List<DispatchPlanItemDO> vehicleItems : itemsByVehicle.values()) {
            vehicleItems.sort(Comparator.comparing(DispatchPlanItemDO::getVisitSequence));
            LocalDateTime eta = departTime;
            StationDO prevStation = null;
            Integer prevAction = null;
            for (DispatchPlanItemDO item : vehicleItems) {
                StationDO station = item.getStationId() != null ? stationMap.get(item.getStationId()) : null;
                // 本段（上一站→本站）路网时长/里程，估算后回写明细（P1-001：segmentDuration 落库）
                Integer segmentSeconds = null;
                BigDecimal segmentKm = null;
                if (prevStation != null) {
                    // 上一站作业分钟
                    if (SERVICE_ACTIONS.contains(prevAction)) {
                        eta = eta.plusMinutes(serviceMinutes);
                    }
                    // 站间行驶：优先算法返回的路网分段时长/里程，缺省回退直线÷均速（坐标缺失按 0 里程）
                    RoadSegment roadSegment = roadSegments.get(item.getVehicleId() + ":" + item.getVisitSequence());
                    if (roadSegment != null && roadSegment.durationSeconds() != null) {
                        segmentSeconds = roadSegment.durationSeconds().intValue();
                        eta = eta.plusSeconds(segmentSeconds);
                    } else if (hasCoords(prevStation) && hasCoords(station)) {
                        double km = GeoDistanceUtil.haversineKm(
                                prevStation.getLongitude().doubleValue(), prevStation.getLatitude().doubleValue(),
                                station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                        segmentSeconds = travelMinutes(km, speedKmh) * 60;
                        eta = eta.plusMinutes(travelMinutes(km, speedKmh));
                    }
                    if (roadSegment != null && roadSegment.distanceKm() != null) {
                        segmentKm = BigDecimal.valueOf(roadSegment.distanceKm());
                    } else if (hasCoords(prevStation) && hasCoords(station)) {
                        double km = GeoDistanceUtil.haversineKm(
                                prevStation.getLongitude().doubleValue(), prevStation.getLatitude().doubleValue(),
                                station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                        segmentKm = BigDecimal.valueOf(km).setScale(3, RoundingMode.HALF_UP);
                    }
                    if (segmentKm != null) {
                        totalKm += segmentKm.doubleValue();
                    }
                }
                // 本站作业时长（接/送/派/揽计停站作业），计划离站 = 到达 + 作业；数量按订单子表回写
                Integer itemServiceSeconds = SERVICE_ACTIONS.contains(item.getActionType())
                        ? serviceMinutes * 60 : 0;
                DispatchPlanItemDO update = new DispatchPlanItemDO();
                update.setId(item.getId());
                update.setEstimatedArrivalTime(eta);
                update.setPlannedDepartureTime(eta.plusSeconds(itemServiceSeconds));
                update.setServiceDurationSeconds(itemServiceSeconds);
                update.setQuantity(quantityOf(item, passengerMap, cargoMap, postalMap));
                update.setSegmentDurationSeconds(segmentSeconds);
                update.setSegmentDistanceKm(segmentKm);
                update.setStatus(TaskItemStatusEnum.PENDING.getStatus());
                dispatchPlanItemMapper.updateById(update);
                prevStation = station;
                prevAction = item.getActionType();
            }
            maxDurationMinutes = Math.max(maxDurationMinutes, Duration.between(departTime, eta).toMinutes());
        }
        // 方案摘要：预计耗时/收入/成本 + ETA 路网来源 + 任务段窗口
        DispatchPlanDO planUpdate = new DispatchPlanDO();
        planUpdate.setId(planId);
        planUpdate.setEstDurationMinutes((int) maxDurationMinutes);
        planUpdate.setRouteProvider(roadSegments.isEmpty() ? "EUCLIDEAN_FALLBACK" : "AMAP");
        planUpdate.setTaskWindowStart(departTime);
        planUpdate.setTaskWindowEnd(departTime.plusMinutes(maxDurationMinutes));
        planUpdate.setEstRevenue(computeRevenue(items, stationMap, rule));
        planUpdate.setEstCost(computeCost(items, rule, totalKm));
        dispatchPlanMapper.updateById(planUpdate);
    }

    /**
     * 货运成本口径（与业务前提一致：**利用公交/大巴空闲运力**，车辆本来就要按线路跑）：
     * 成本 = 绕行里程成本（真实增量）+ 货运分摊的骨架里程成本（公交运营成本的一部分，按比例分摊）
     *      + 停站作业时间成本。
     *
     * <p>为什么不再用"全程里程 × 车辆单价"：那等于让几张包裹订单承担整趟公交的成本，
     * 真实场景里车的固定成本由客运/财政补贴承担，货运只应承担**增量成本 + 合理分摊**，
     * 否则方案永远显示亏本（演示里"全是亏本运行"就是这个口径造成的）。</p>
     */
    private BigDecimal computeCost(List<DispatchPlanItemDO> items, PricingRuleDO rule, double totalKm) {
        if (rule.getVehicleCostPerKm() == null) {
            return null;
        }
        // 1) 绕行里程（算法给出的相对公交骨架的增量里程；没有时退回 0）
        double detourKm = items.stream()
                .map(DispatchPlanItemDO::getDetourDistanceKm)
                .filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();
        // 2) 骨架里程里由货运分摊的比例（默认 35%：其余由客运/补贴承担）
        double sharedKm = totalKm * CARGO_COST_SHARE;
        double billableKm = detourKm + sharedKm;
        BigDecimal cost = rule.getVehicleCostPerKm()
                .multiply(BigDecimal.valueOf(billableKm));
        // 3) 停站作业时间成本（按车辆公里成本 ÷ 均速折算每分钟成本）
        double speed = rule.getAvgSpeedKmh() != null && rule.getAvgSpeedKmh().doubleValue() > 0
                ? rule.getAvgSpeedKmh().doubleValue() : 25.0;
        int serviceMinutes = rule.getStopServiceMinutes() != null ? rule.getStopServiceMinutes() : 3;
        long stops = items.stream().filter(i -> SERVICE_ACTIONS.contains(i.getActionType())).count();
        BigDecimal perMinute = rule.getVehicleCostPerKm()
                .divide(BigDecimal.valueOf(speed), 4, RoundingMode.HALF_UP);
        cost = cost.add(perMinute.multiply(BigDecimal.valueOf(stops * (long) serviceMinutes)));
        return cost.setScale(2, RoundingMode.HALF_UP);
    }

    /** 货运分摊的骨架里程比例（其余由客运/财政补贴承担） */
    private static final double CARGO_COST_SHARE = 0.35;
    /** 直线距离 → 路网里程折算系数（计价用，避免为计价再调一次高德） */
    /** 路网系数：直线距离 → 计费里程（寄货页试算 CargoPricingService 复用同一系数，避免两处口径漂移） */
    public static final double ROAD_FACTOR_FOR_PRICE = 1.3;

    /** 明细数量：BOARD/ALIGHT=客运人数，PICKUP/DELIVERY=货运/邮快件件数（子表缺失按 1 兜底） */
    private Integer quantityOf(DispatchPlanItemDO item, Map<Long, PassengerOrderDO> passengerMap,
                               Map<Long, CargoOrderDO> cargoMap, Map<Long, PostalOrderDO> postalMap) {
        if (item.getOrderId() == null) {
            return null;
        }
        Integer action = item.getActionType();
        if (PlanItemActionEnum.BOARD.getAction().equals(action)
                || PlanItemActionEnum.ALIGHT.getAction().equals(action)) {
            PassengerOrderDO passenger = passengerMap.get(item.getOrderId());
            return passenger != null && passenger.getPassengerCount() != null
                    ? passenger.getPassengerCount() : 1;
        }
        CargoOrderDO cargo = cargoMap.get(item.getOrderId());
        if (cargo != null) {
            return cargo.getItemCount() != null ? cargo.getItemCount() : 1;
        }
        PostalOrderDO postal = postalMap.get(item.getOrderId());
        return postal != null && postal.getItemCount() != null ? postal.getItemCount() : 1;
    }

    /**
     * 预计收入：客运 人数×上下车站直线公里×人公里价（坐标缺失按 0 计）；
     * 货运/邮快件 件数×件单价。订单子表缺失时按 1 人/1 件兜底。
     */
    private BigDecimal computeRevenue(List<DispatchPlanItemDO> items, Map<Long, StationDO> stationMap,
                                      PricingRuleDO rule) {
        Set<Long> orderIds = items.stream().map(DispatchPlanItemDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (orderIds.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        Map<Long, TransportOrderDO> orderMap = orderMapper.selectBatchIds(orderIds).stream()
                .collect(Collectors.toMap(TransportOrderDO::getId, Function.identity(), (a, b) -> a));
        BigDecimal revenue = BigDecimal.ZERO;
        for (TransportOrderDO order : orderMap.values()) {
            if (Objects.equals(order.getOrderType(), 1)) { // 客运
                PassengerOrderDO passenger = passengerOrderMapper.selectOne(
                        PassengerOrderDO::getOrderId, order.getId());
                int count = passenger != null && passenger.getPassengerCount() != null
                        ? passenger.getPassengerCount() : 1;
                StationDO from = stationMap.get(order.getPickupStationId());
                StationDO to = stationMap.get(order.getDeliveryStationId());
                if (hasCoords(from) && hasCoords(to) && rule.getPassengerPricePerKm() != null) {
                    double km = GeoDistanceUtil.haversineKm(
                            from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                            to.getLongitude().doubleValue(), to.getLatitude().doubleValue());
                    revenue = revenue.add(rule.getPassengerPricePerKm()
                            .multiply(BigDecimal.valueOf(km)).multiply(BigDecimal.valueOf(count)));
                }
            } else if (Objects.equals(order.getOrderType(), 2)) { // 货运
                CargoOrderDO cargo = cargoOrderMapper.selectOne(CargoOrderDO::getOrderId, order.getId());
                int count = cargo != null && cargo.getItemCount() != null ? cargo.getItemCount() : 1;
                if (rule.getCargoPricePerItem() != null) {
                    revenue = revenue.add(rule.getCargoPricePerItem().multiply(BigDecimal.valueOf(count)));
                }
                // 里程费：按"取货站→送达站"路网里程计价（起步价=件单价，公里费=人公里价的货运口径）
                revenue = revenue.add(distanceFee(order, stationMap, rule));
            } else if (Objects.equals(order.getOrderType(), 3)) { // 邮快件
                PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
                int count = postal != null && postal.getItemCount() != null ? postal.getItemCount() : 1;
                if (rule.getPostalPricePerItem() != null) {
                    revenue = revenue.add(rule.getPostalPricePerItem().multiply(BigDecimal.valueOf(count)));
                }
                revenue = revenue.add(distanceFee(order, stationMap, rule));
            }
        }
        return revenue.setScale(2, RoundingMode.HALF_UP);
    }

    /** 里程费：直线距离 × 路网系数 × 里程单价（货运/邮快件按件里程计；坐标缺失按 0） */
    private BigDecimal distanceFee(TransportOrderDO order, Map<Long, StationDO> stationMap, PricingRuleDO rule) {
        if (rule.getPassengerPricePerKm() == null) {
            return BigDecimal.ZERO;
        }
        StationDO from = stationMap.get(order.getPickupStationId());
        StationDO to = stationMap.get(order.getDeliveryStationId());
        if (!hasCoords(from) || !hasCoords(to)) {
            return BigDecimal.ZERO;
        }
        double km = GeoDistanceUtil.haversineKm(
                from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                to.getLongitude().doubleValue(), to.getLatitude().doubleValue()) * ROAD_FACTOR_FOR_PRICE;
        return rule.getPassengerPricePerKm().multiply(BigDecimal.valueOf(km));
    }

    private static boolean hasCoords(StationDO station) {
        return station != null && station.getLongitude() != null && station.getLatitude() != null;
    }

    /** 站间行驶分钟：按均速换算四舍五入；0 里程即 0 分钟（不做提醒场景的最少 1 分钟收口，避免同站经停虚增） */
    static int travelMinutes(double distKm, double avgSpeedKmh) {
        if (avgSpeedKmh <= 0) {
            return 0;
        }
        return (int) Math.round(distKm / avgSpeedKmh * 60);
    }
}
