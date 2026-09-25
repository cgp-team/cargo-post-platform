package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.TransportLegMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
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
        return candidates(pending, excludeLegId, vehicleId, null, start, end).stream()
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
        return candidates(pending, excludeLegId, null, driverId, start, end).stream()
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

    /**
     * 候选集合 = 库中未完结订单的运输段 + 本次在规划中的段。
     * BE-15：库中部分改为按资源维度 + 时间窗 + 状态的条件查询（禁止无条件 selectList 全表载入）；
     * 时间窗语义与 overlaps 一致：占用区间 [departure-BUF, arrival+BUF] 与 [start, end] 相交，
     * 即 departure <= end+BUF 且 arrival >= start-BUF。estimated 为 NULL 的段 SQL 不命中
     * ——与旧行为一致（overlaps 对 null 返回 false，本就不参与冲突）。
     */
    private List<TransportLegDO> candidates(Collection<TransportLegDO> pending, Long excludeLegId,
                                            Long vehicleId, Long driverId,
                                            LocalDateTime start, LocalDateTime end) {
        List<TransportLegDO> all = new ArrayList<>();
        if (pending != null) {
            all.addAll(pending);
        }
        LambdaQueryWrapperX<TransportLegDO> query = new LambdaQueryWrapperX<TransportLegDO>()
                // 占用中的段不含已完成（与 occupies 内存过滤同口径）
                .eqIfPresent(TransportLegDO::getVehicleId, vehicleId)
                .eqIfPresent(TransportLegDO::getDriverId, driverId);
        // ne 在 eqIfPresent 之后调用：ne 返回父类 LambdaQueryWrapper，链上再调 eqIfPresent 会编译失败
        query.ne(TransportLegDO::getStatus, TransportLegStatusEnum.COMPLETED.getStatus());
        if (start != null && end != null) {
            query.le(TransportLegDO::getEstimatedDeparture, end.plusMinutes(BUFFER_MINUTES))
                    .ge(TransportLegDO::getEstimatedArrival, start.minusMinutes(BUFFER_MINUTES));
        }
        List<TransportLegDO> stored = legMapper.selectList(query);
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
