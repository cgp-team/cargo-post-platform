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

    /**
     * 估算并回写方案全部经停明细的预计到达时间，以及方案预计耗时/收入/成本。
     * 坐标缺失的站段按 0 里程处理（不中断后续估算）；无经停明细或出发时刻为 null 时不处理。
     *
     * @param planId     方案编号
     * @param departTime 计划出发时刻（当前口径 = 批次开始时刻）
     */
    public void estimatePlan(Long planId, LocalDateTime departTime) {
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
                if (prevStation != null) {
                    // 上一站作业分钟 + 站间行驶分钟（坐标缺失按 0 里程）
                    if (SERVICE_ACTIONS.contains(prevAction)) {
                        eta = eta.plusMinutes(serviceMinutes);
                    }
                    if (hasCoords(prevStation) && hasCoords(station)) {
                        double km = GeoDistanceUtil.haversineKm(
                                prevStation.getLongitude().doubleValue(), prevStation.getLatitude().doubleValue(),
                                station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                        eta = eta.plusMinutes(travelMinutes(km, speedKmh));
                        totalKm += km;
                    }
                }
                DispatchPlanItemDO update = new DispatchPlanItemDO();
                update.setId(item.getId());
                update.setEstimatedArrivalTime(eta);
                dispatchPlanItemMapper.updateById(update);
                prevStation = station;
                prevAction = item.getActionType();
            }
            maxDurationMinutes = Math.max(maxDurationMinutes, Duration.between(departTime, eta).toMinutes());
        }
        // 方案摘要：预计耗时/收入/成本
        DispatchPlanDO planUpdate = new DispatchPlanDO();
        planUpdate.setId(planId);
        planUpdate.setEstDurationMinutes((int) maxDurationMinutes);
        planUpdate.setEstRevenue(computeRevenue(items, stationMap, rule));
        planUpdate.setEstCost(rule.getVehicleCostPerKm() != null
                ? rule.getVehicleCostPerKm().multiply(BigDecimal.valueOf(totalKm)).setScale(2, RoundingMode.HALF_UP)
                : null);
        dispatchPlanMapper.updateById(planUpdate);
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
            } else if (Objects.equals(order.getOrderType(), 3)) { // 邮快件
                PostalOrderDO postal = postalOrderMapper.selectOne(PostalOrderDO::getOrderId, order.getId());
                int count = postal != null && postal.getItemCount() != null ? postal.getItemCount() : 1;
                if (rule.getPostalPricePerItem() != null) {
                    revenue = revenue.add(rule.getPostalPricePerItem().multiply(BigDecimal.valueOf(count)));
                }
            }
        }
        return revenue.setScale(2, RoundingMode.HALF_UP);
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
