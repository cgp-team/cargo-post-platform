package cn.iocoder.yudao.module.transport.service.dispatch;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务段完成校验：ARRIVED != COMPLETED。
 *
 * <p>货运段完成 = 到站 + 必要动作（PICKUP / DELIVERY / HANDOVER）全部完成。
 * MultiLeg 订单只有最终 DELIVERY 成功才算 ORDER_COMPLETED。
 */
@Service
public class TaskSegmentCompletionService {

    public enum SegmentStatus {
        PLANNED, IN_PROGRESS, ARRIVED, SEGMENT_COMPLETED, FAILED
    }

    public enum OrderStatus {
        PLANNED, IN_TRANSIT, ORDER_COMPLETED, FAILED
    }

    public record SegmentResult(boolean ok, SegmentStatus status, String reasonCode) {
    }

    /**
     * @param arrived            是否已到站
     * @param requiredActions    必要动作（PICKUP/DELIVERY/HANDOVER/...）
     * @param completedActions   已完成动作
     * @param handoverConfirmed  HANDOVER 是否确认；非 HANDOVER 段可传 null
     */
    public SegmentResult validateSegment(
            boolean arrived,
            Collection<String> requiredActions,
            Collection<String> completedActions,
            Boolean handoverConfirmed) {
        if (!arrived) {
            return new SegmentResult(false, SegmentStatus.IN_PROGRESS, "NOT_ARRIVED");
        }
        Set<String> req = requiredActions == null ? Set.of() : new HashSet<>(requiredActions);
        Set<String> done = completedActions == null ? Set.of() : new HashSet<>(completedActions);
        Set<String> missing = new HashSet<>(req);
        missing.removeAll(done);
        if (!missing.isEmpty()) {
            return new SegmentResult(false, SegmentStatus.ARRIVED,
                    "ACTIONS_INCOMPLETE:" + String.join(",", missing.stream().sorted().toList()));
        }
        if (req.contains("HANDOVER") && Boolean.FALSE.equals(handoverConfirmed)) {
            return new SegmentResult(false, SegmentStatus.ARRIVED, "HANDOVER_NOT_CONFIRMED");
        }
        return new SegmentResult(true, SegmentStatus.SEGMENT_COMPLETED, "SEGMENT_COMPLETED");
    }

    /**
     * 整单状态：所有 leg 完成且最终 DELIVERY 完成 → ORDER_COMPLETED，否则 IN_TRANSIT。
     */
    public OrderStatus orderStatus(List<SegmentStatus> legStatuses, boolean finalDeliveryCompleted) {
        if (legStatuses == null || legStatuses.isEmpty()) {
            return OrderStatus.FAILED;
        }
        if (!finalDeliveryCompleted) {
            return OrderStatus.IN_TRANSIT;
        }
        boolean allCompleted = legStatuses.stream().allMatch(s -> s == SegmentStatus.SEGMENT_COMPLETED);
        return allCompleted ? OrderStatus.ORDER_COMPLETED : OrderStatus.IN_TRANSIT;
    }

    /** 计划项是否在发车后锁定（普通新订单不得再直接追加）。 */
    public boolean isLockedAfterDeparture(String planItemStatus) {
        if (planItemStatus == null) {
            return false;
        }
        return switch (planItemStatus) {
            case "DEPARTED", "IN_PROGRESS", "ARRIVED", "LOCKED_ACTIVE_TRIP" -> true;
            default -> false;
        };
    }

    public boolean isTerminal(String planItemStatus) {
        return "COMPLETED".equals(planItemStatus) || "FAILED".equals(planItemStatus);
    }
}
