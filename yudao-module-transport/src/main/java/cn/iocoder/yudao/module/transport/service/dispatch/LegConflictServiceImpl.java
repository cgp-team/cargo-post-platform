package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_TIME_CONFLICT;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.VEHICLE_TIME_CONFLICT;

/**
 * 资源冲突检测实现：把"库中已有运输段"与"本次在规划中的运输段"合并后判定时间重叠。
 */
@Service
@Validated
@Slf4j
public class LegConflictServiceImpl implements LegConflictService {

    /** 交接/装卸缓冲（分钟）：相邻任务之间预留的作业时间 */
    public static final int BUFFER_MINUTES = 15;

    @Resource private TransportLegMapper legMapper;
    @Resource private TransportOrderMapper orderMapper;

    @Override
    public List<TransportLegDO> findVehicleConflicts(Long vehicleId, LocalDateTime start, LocalDateTime end,
                                                     Collection<TransportLegDO> pending, Long excludeLegId) {
        if (vehicleId == null) {
            return List.of();
        }
        return candidates(pending, excludeLegId).stream()
                .filter(LegConflictService::occupies)
                .filter(l -> Objects.equals(l.getVehicleId(), vehicleId))
                .filter(l -> LegConflictService.overlaps(start, end, l.getEstimatedDeparture(),
                        l.getEstimatedArrival(), BUFFER_MINUTES))
                .toList();
    }

    @Override
    public List<TransportLegDO> findDriverConflicts(Long driverId, LocalDateTime start, LocalDateTime end,
                                                    Collection<TransportLegDO> pending, Long excludeLegId) {
        if (driverId == null) {
            return List.of();
        }
        return candidates(pending, excludeLegId).stream()
                .filter(LegConflictService::occupies)
                .filter(l -> Objects.equals(l.getDriverId(), driverId))
                .filter(l -> LegConflictService.overlaps(start, end, l.getEstimatedDeparture(),
                        l.getEstimatedArrival(), BUFFER_MINUTES))
                .toList();
    }

    @Override
    public boolean vehicleConflicts(Long vehicleId, LocalDateTime start, LocalDateTime end,
                                    Collection<TransportLegDO> pending, Long excludeLegId) {
        return !findVehicleConflicts(vehicleId, start, end, pending, excludeLegId).isEmpty();
    }

    @Override
    public boolean driverConflicts(Long driverId, LocalDateTime start, LocalDateTime end,
                                   Collection<TransportLegDO> pending, Long excludeLegId) {
        return !findDriverConflicts(driverId, start, end, pending, excludeLegId).isEmpty();
    }

    @Override
    public void assertNoConflict(Long vehicleId, Long driverId, LocalDateTime start, LocalDateTime end,
                                 Collection<TransportLegDO> pending, Long excludeLegId) {
        List<TransportLegDO> vehicleHits = findVehicleConflicts(vehicleId, start, end, pending, excludeLegId);
        if (!vehicleHits.isEmpty()) {
            throw exception(VEHICLE_TIME_CONFLICT, describe(vehicleHits.get(0)));
        }
        List<TransportLegDO> driverHits = findDriverConflicts(driverId, start, end, pending, excludeLegId);
        if (!driverHits.isEmpty()) {
            throw exception(DRIVER_TIME_CONFLICT, describe(driverHits.get(0)));
        }
    }

    /** 候选集合 = 库中未完结订单的运输段 + 本次在规划中的段 */
    private List<TransportLegDO> candidates(Collection<TransportLegDO> pending, Long excludeLegId) {
        List<TransportLegDO> all = new ArrayList<>();
        if (pending != null) {
            all.addAll(pending);
        }
        List<TransportLegDO> stored = legMapper.selectList();
        if (!stored.isEmpty()) {
            Set<Long> orderIds = stored.stream().map(TransportLegDO::getOrderId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            Set<Long> finishedOrderIds = orderIds.isEmpty() ? Set.of()
                    : orderMapper.selectBatchIds(orderIds).stream()
                    .filter(o -> !LegConflictService.orderActive(o.getStatus()))
                    .map(TransportOrderDO::getId).collect(Collectors.toSet());
            stored.stream()
                    .filter(l -> !finishedOrderIds.contains(l.getOrderId()))
                    .forEach(all::add);
        }
        return all.stream()
                .filter(l -> excludeLegId == null || !Objects.equals(l.getId(), excludeLegId))
                .toList();
    }

    private static String describe(TransportLegDO leg) {
        return "订单 " + leg.getOrderId() + " 第 " + leg.getLegSequence() + " 段（"
                + leg.getEstimatedDeparture() + " ~ " + leg.getEstimatedArrival() + "）";
    }

}
