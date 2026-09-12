package cn.iocoder.yudao.module.transport.enums.dispatch;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 运输段状态（多段联运：一个订单拆成多段，每段由不同车辆/司机承运）。
 *
 * 对应 transport_leg.status。司机端按钮状态机（见需求 §6/§58）：
 * 已规划 → 已分配 → [接受任务] → 等待发车 → [开始导航] → 前往起点 → [到达起点]
 * → 装货中 → [开始运输] → 运输中 → [到达终点] → 到达终点
 * → 中间段：交接中（完成交接后 COMPLETED）；最终段：派送中 → [完成配送] → 已完成
 *
 * 禁止跳级（如 运输中 直接 已完成），非法流转由 {@link #canTransit} 拦截（需求 §115）。
 */
@Getter
@AllArgsConstructor
public enum TransportLegStatusEnum {

    PLANNED(0, "已规划"),
    ASSIGNED(1, "已分配"),
    DRIVER_ACCEPTED(2, "司机已接单"),
    WAITING_START(3, "等待发车"),
    NAVIGATING(4, "前往起点"),
    ARRIVED_ORIGIN(5, "已到达起点"),
    LOADING(6, "装货中"),
    IN_TRANSIT(7, "运输中"),
    ARRIVED_DESTINATION(8, "已到达终点"),
    HANDOVER(9, "交接中"),
    DELIVERING(10, "派送中"),
    COMPLETED(11, "已完成"),
    EXCEPTION(99, "异常");

    private final Integer status;
    private final String name;

    /** 合法流转表（key=当前状态，value=允许到达的状态） */
    private static final Map<TransportLegStatusEnum, Set<TransportLegStatusEnum>> TRANSITIONS = Map.ofEntries(
            Map.entry(PLANNED, EnumSet.of(ASSIGNED, EXCEPTION)),
            Map.entry(ASSIGNED, EnumSet.of(DRIVER_ACCEPTED, PLANNED, EXCEPTION)),
            Map.entry(DRIVER_ACCEPTED, EnumSet.of(WAITING_START, NAVIGATING, EXCEPTION)),
            Map.entry(WAITING_START, EnumSet.of(NAVIGATING, EXCEPTION)),
            Map.entry(NAVIGATING, EnumSet.of(ARRIVED_ORIGIN, EXCEPTION)),
            Map.entry(ARRIVED_ORIGIN, EnumSet.of(LOADING, EXCEPTION)),
            Map.entry(LOADING, EnumSet.of(IN_TRANSIT, EXCEPTION)),
            Map.entry(IN_TRANSIT, EnumSet.of(ARRIVED_DESTINATION, EXCEPTION)),
            Map.entry(ARRIVED_DESTINATION, EnumSet.of(HANDOVER, DELIVERING, EXCEPTION)),
            Map.entry(HANDOVER, EnumSet.of(COMPLETED, EXCEPTION)),
            Map.entry(DELIVERING, EnumSet.of(COMPLETED, EXCEPTION)),
            Map.entry(COMPLETED, EnumSet.noneOf(TransportLegStatusEnum.class)),
            Map.entry(EXCEPTION, EnumSet.of(PLANNED, ASSIGNED, DRIVER_ACCEPTED, WAITING_START)));

    /** 是否允许从 from 流转到 to（同状态视为幂等放行） */
    public static boolean canTransit(TransportLegStatusEnum from, TransportLegStatusEnum to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static TransportLegStatusEnum of(Integer status) {
        if (status == null) {
            return null;
        }
        for (TransportLegStatusEnum item : values()) {
            if (item.getStatus().equals(status)) {
                return item;
            }
        }
        return null;
    }

    public static String nameOf(Integer status) {
        TransportLegStatusEnum item = of(status);
        return item == null ? "" : item.getName();
    }

}
