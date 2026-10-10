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
        return findVehicleConflicts(vehicleId, start, end, pending, excludeLegId, null);
    }

    @Override
    public List<TransportLegDO> findVehicleConflicts(Long vehicleId, LocalDateTime start, LocalDateTime end,
                                                     Collection<TransportLegDO> pending, Long excludeLegId,
                                                     ConflictSnapshot snapshot) {
        if (vehicleId == null) {
            return List.of();
        }
        return candidates(pending, excludeLegId, vehicleId, null, start, end, snapshot).stream()
                .filter(LegConflictService::occupies)
                .filter(l -> Objects.equals(l.getVehicleId(), vehicleId))
                .filter(l -> LegConflictService.overlaps(start, end, l.getEstimatedDeparture(),
                        l.getEstimatedArrival(), BUFFER_MINUTES))
                .toList();
    }

    @Override
    public List<TransportLegDO> findDriverConflicts(Long driverId, LocalDateTime start, LocalDateTime end,
                                                    Collection<TransportLegDO> pending, Long excludeLegId) {
        return findDriverConflicts(driverId, start, end, pending, excludeLegId, null);
    }

    @Override
    public List<TransportLegDO> findDriverConflicts(Long driverId, LocalDateTime start, LocalDateTime end,
                                                    Collection<TransportLegDO> pending, Long excludeLegId,
                                                    ConflictSnapshot snapshot) {
        if (driverId == null) {
            return List.of();
        }
        return candidates(pending, excludeLegId, null, driverId, start, end, snapshot).stream()
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
     *
     * snapshot 非空时库中部分退化为内存过滤（预取已是「资源集合 × 时间窗超集 × 未完结订单」的
     * 全量候选），循环内反复判定不再重复查库。
     */
    private List<TransportLegDO> candidates(Collection<TransportLegDO> pending, Long excludeLegId,
                                            Long vehicleId, Long driverId,
                                            LocalDateTime start, LocalDateTime end,
                                            ConflictSnapshot snapshot) {
        List<TransportLegDO> all = new ArrayList<>();
        if (pending != null) {
            all.addAll(pending);
        }
        if (snapshot != null) {
            // 内存路径：与 SQL 路径同口径（资源等值 + 时间窗相交；NULL estimated 不命中）
            for (TransportLegDO l : snapshot.storedLegs()) {
                if (vehicleId != null && !Objects.equals(l.getVehicleId(), vehicleId)) {
                    continue;
                }
                if (driverId != null && !Objects.equals(l.getDriverId(), driverId)) {
                    continue;
                }
                if (start != null && end != null && !withinWindow(l, start, end)) {
                    continue;
                }
                all.add(l);
            }
        } else {
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
            all.addAll(excludeFinishedOrders(legMapper.selectList(query)));
        }
        return all.stream()
                .filter(l -> excludeLegId == null || !Objects.equals(l.getId(), excludeLegId))
                .toList();
    }

    @Override
    public ConflictSnapshot preloadConflicts(Collection<Long> vehicleIds, Collection<Long> driverIds,
                                             LocalDateTime start, LocalDateTime end) {
        List<Long> vIds = vehicleIds == null ? List.of()
                : vehicleIds.stream().filter(Objects::nonNull).distinct().toList();
        List<Long> dIds = driverIds == null ? List.of()
                : driverIds.stream().filter(Objects::nonNull).distinct().toList();
        if (vIds.isEmpty() && dIds.isEmpty()) {
            return new ConflictSnapshot(List.of());
        }
        // 资源维度：vehicle_id IN (...) OR driver_id IN (...)（空集合不下发 IN ()，避免语法错误）
        LambdaQueryWrapperX<TransportLegDO> query = new LambdaQueryWrapperX<>();
        // and/ne 返回父类 LambdaQueryWrapper，不能链回 X——分开调用（与下方 candidates 同注意点）
        query.and(w -> {
            if (!vIds.isEmpty() && !dIds.isEmpty()) {
                w.in(TransportLegDO::getVehicleId, vIds).or().in(TransportLegDO::getDriverId, dIds);
            } else if (!vIds.isEmpty()) {
                w.in(TransportLegDO::getVehicleId, vIds);
            } else {
                w.in(TransportLegDO::getDriverId, dIds);
            }
        });
        query.ne(TransportLegDO::getStatus, TransportLegStatusEnum.COMPLETED.getStatus());
        if (start != null && end != null) {
            query.le(TransportLegDO::getEstimatedDeparture, end.plusMinutes(BUFFER_MINUTES))
                    .ge(TransportLegDO::getEstimatedArrival, start.minusMinutes(BUFFER_MINUTES));
        }
        return new ConflictSnapshot(excludeFinishedOrders(legMapper.selectList(query)));
    }

    /** 与 SQL 时间窗同口径：departure <= end+BUF 且 arrival >= start-BUF；estimated 为 NULL 不命中 */
    private static boolean withinWindow(TransportLegDO leg, LocalDateTime start, LocalDateTime end) {
        if (leg.getEstimatedDeparture() == null || leg.getEstimatedArrival() == null) {
            return false;
        }
        return !leg.getEstimatedDeparture().isAfter(end.plusMinutes(BUFFER_MINUTES))
                && !leg.getEstimatedArrival().isBefore(start.minusMinutes(BUFFER_MINUTES));
    }

    /** 剔除已终态（已完成/已取消）订单的运输段：终态订单不再占用资源 */
    private List<TransportLegDO> excludeFinishedOrders(List<TransportLegDO> stored) {
        if (stored.isEmpty()) {
            return stored;
        }
        Set<Long> orderIds = stored.stream().map(TransportLegDO::getOrderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> finishedOrderIds = orderIds.isEmpty() ? Set.of()
                : orderMapper.selectBatchIds(orderIds).stream()
                .filter(o -> !LegConflictService.orderActive(o.getStatus()))
                .map(TransportOrderDO::getId).collect(Collectors.toSet());
        return stored.stream()
                .filter(l -> !finishedOrderIds.contains(l.getOrderId()))
                .toList();
    }

    private static String describe(TransportLegDO leg) {
        return "订单 " + leg.getOrderId() + " 第 " + leg.getLegSequence() + " 段（"
                + leg.getEstimatedDeparture() + " ~ " + leg.getEstimatedArrival() + "）";
    }

}
