package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportOrderStatusEnum;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 运输段资源冲突检测（车辆 / 司机时间冲突，需求 §48/§49/§52/§113）。
 *
 * 规则：同一车辆/司机，其"占用区间"（预计出发 - 缓冲 ~ 预计到达 + 缓冲）不能与其他**未完成**运输段重叠。
 * - 手工调度同样要过这道检查（不相信前端，§52）；
 * - 已完成/已取消订单的运输段不再占用资源；
 * - 同一批次内新规划的段也要互相避让（调用方把"在规划中的段"一并传入）。
 */
public interface LegConflictService {

    /** 与给定车辆冲突的已有运输段（不含 excludeLegId） */
    List<TransportLegDO> findVehicleConflicts(Long vehicleId, LocalDateTime start, LocalDateTime end,
                                              Collection<TransportLegDO> pending, Long excludeLegId);

    /** 与给定司机冲突的已有运输段 */
    List<TransportLegDO> findDriverConflicts(Long driverId, LocalDateTime start, LocalDateTime end,
                                             Collection<TransportLegDO> pending, Long excludeLegId);

    /** 车辆是否时段冲突 */
    boolean vehicleConflicts(Long vehicleId, LocalDateTime start, LocalDateTime end,
                             Collection<TransportLegDO> pending, Long excludeLegId);

    /** 司机是否时段冲突 */
    boolean driverConflicts(Long driverId, LocalDateTime start, LocalDateTime end,
                            Collection<TransportLegDO> pending, Long excludeLegId);

    /**
     * 校验车辆+司机在该时段均无冲突，冲突则抛业务异常（手工派单/立即分配前调用）。
     */
    void assertNoConflict(Long vehicleId, Long driverId, LocalDateTime start, LocalDateTime end,
                          Collection<TransportLegDO> pending, Long excludeLegId);

    /** 两个区间是否重叠（含缓冲分钟） */
    static boolean overlaps(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd,
                            int bufferMinutes) {
        if (aStart == null || aEnd == null || bStart == null || bEnd == null) {
            return false;
        }
        LocalDateTime bufferedAStart = aStart.minusMinutes(bufferMinutes);
        LocalDateTime bufferedAEnd = aEnd.plusMinutes(bufferMinutes);
        return bufferedAStart.isBefore(bEnd) && bStart.isBefore(bufferedAEnd);
    }

    /** 段是否需要占用资源（已完成/异常/取消后的段不再占用） */
    static boolean occupies(TransportLegDO leg) {
        return leg != null && !TransportLegStatusEnum.COMPLETED.getStatus().equals(leg.getStatus());
    }

    /** 订单终态不占用资源 */
    static boolean orderActive(Integer orderStatus) {
        return orderStatus == null || (!TransportOrderStatusEnum.COMPLETED.getStatus().equals(orderStatus)
                && !TransportOrderStatusEnum.CANCELLED.getStatus().equals(orderStatus));
    }

}
