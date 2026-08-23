package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanItemMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.PlanItemActionEnum;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 调度方案估算：按经停序列估算每站预计到达时间（ETA）并回写 plan item。
 *
 * 契约约定算法服务不估算耗时（见 docs/algorithm-integration.md 适配层责任 6），
 * 故由业务后端自行估算：从批次开始时刻出发，逐站累计 Haversine 里程/均速 + 停站作业分钟。
 * 估算结果服务于小程序包裹追踪 ETA 与司机端任务列表 ETA。
 */
@Service
public class DispatchEstimationService {

    /** 乡村班线平均时速（km/h）；后续由计价规则配置覆盖（见 transport_pricing_rule 迭代） */
    static final double AVG_SPEED_KMH = 25.0;
    /** 作业站（接客/送客/派送/揽收）停站分钟；DEPART/RETURN 不计 */
    static final int STOP_SERVICE_MINUTES = 3;
    /** 计停站作业分钟的动作类型 */
    private static final Set<Integer> SERVICE_ACTIONS = Set.of(
            PlanItemActionEnum.BOARD.getAction(), PlanItemActionEnum.ALIGHT.getAction(),
            PlanItemActionEnum.DELIVER.getAction(), PlanItemActionEnum.PICKUP.getAction());

    @Resource private DispatchPlanItemMapper dispatchPlanItemMapper;
    @Resource private StationMapper stationMapper;

    /**
     * 估算并回写方案全部经停明细的预计到达时间。
     * 坐标缺失的站段按 0 里程处理（不中断后续估算）；无经停明细或出发时刻为 null 时不处理。
     *
     * @param planId     方案编号
     * @param departTime 计划出发时刻（当前口径 = 批次开始时刻）
     */
    public void estimateAndFillPlanEtas(Long planId, LocalDateTime departTime) {
        if (planId == null || departTime == null) {
            return;
        }
        List<DispatchPlanItemDO> items = dispatchPlanItemMapper.selectList(
                new LambdaQueryWrapperX<DispatchPlanItemDO>().eq(DispatchPlanItemDO::getPlanId, planId));
        if (items.isEmpty()) {
            return;
        }
        // 站点坐标一次加载，消除逐站查询
        Set<Long> stationIds = items.stream().map(DispatchPlanItemDO::getStationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, StationDO> stationMap = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectBatchIds(stationIds).stream()
                        .collect(Collectors.toMap(StationDO::getId, Function.identity(), (a, b) -> a));
        // 按车分组、访问顺序升序，逐车独立累计
        Map<Long, List<DispatchPlanItemDO>> itemsByVehicle = items.stream()
                .filter(item -> item.getVehicleId() != null)
                .collect(Collectors.groupingBy(DispatchPlanItemDO::getVehicleId));
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
                        eta = eta.plusMinutes(STOP_SERVICE_MINUTES);
                    }
                    if (hasCoords(prevStation) && hasCoords(station)) {
                        double km = GeoDistanceUtil.haversineKm(
                                prevStation.getLongitude().doubleValue(), prevStation.getLatitude().doubleValue(),
                                station.getLongitude().doubleValue(), station.getLatitude().doubleValue());
                        eta = eta.plusMinutes(travelMinutes(km, AVG_SPEED_KMH));
                    }
                }
                DispatchPlanItemDO update = new DispatchPlanItemDO();
                update.setId(item.getId());
                update.setEstimatedArrivalTime(eta);
                dispatchPlanItemMapper.updateById(update);
                prevStation = station;
                prevAction = item.getActionType();
            }
        }
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
