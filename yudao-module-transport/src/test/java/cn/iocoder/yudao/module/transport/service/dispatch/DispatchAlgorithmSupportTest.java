package cn.iocoder.yudao.module.transport.service.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 段完成 / 发车锁定 / 交接 / 可达性 单元测试。
 */
class DispatchAlgorithmSupportTest {

    private final TaskSegmentCompletionService segmentService = new TaskSegmentCompletionService();
    private final HandoverFeasibilityService handoverService = new HandoverFeasibilityService();
    private final ReachabilityDecisionService reachabilityService = new ReachabilityDecisionService();

    @Test
    void arrivedIsNotCompleted() {
        var r = segmentService.validateSegment(true, Set.of("PICKUP"), Set.of(), null);
        assertFalse(r.ok());
        assertEquals(TaskSegmentCompletionService.SegmentStatus.ARRIVED, r.status());
        assertTrue(r.reasonCode().startsWith("ACTIONS_INCOMPLETE"));
    }

    @Test
    void pickupCompleteRequired() {
        var r = segmentService.validateSegment(true, Set.of("PICKUP"), Set.of("PICKUP"), null);
        assertTrue(r.ok());
        assertEquals(TaskSegmentCompletionService.SegmentStatus.SEGMENT_COMPLETED, r.status());
    }

    @Test
    void handoverUnconfirmedNotComplete() {
        var r = segmentService.validateSegment(true, Set.of("HANDOVER"), Set.of("HANDOVER"), false);
        assertFalse(r.ok());
        assertEquals("HANDOVER_NOT_CONFIRMED", r.reasonCode());
    }

    @Test
    void multiLegOrderCompleteOnlyAtFinalDelivery() {
        var mid = segmentService.orderStatus(
                List.of(TaskSegmentCompletionService.SegmentStatus.SEGMENT_COMPLETED,
                        TaskSegmentCompletionService.SegmentStatus.IN_PROGRESS),
                false);
        assertEquals(TaskSegmentCompletionService.OrderStatus.IN_TRANSIT, mid);
        var done = segmentService.orderStatus(
                List.of(TaskSegmentCompletionService.SegmentStatus.SEGMENT_COMPLETED,
                        TaskSegmentCompletionService.SegmentStatus.SEGMENT_COMPLETED),
                true);
        assertEquals(TaskSegmentCompletionService.OrderStatus.ORDER_COMPLETED, done);
    }

    @Test
    void tripLockAfterDeparture() {
        assertTrue(segmentService.isLockedAfterDeparture("DEPARTED"));
        assertFalse(segmentService.isLockedAfterDeparture("PLANNED"));
        assertTrue(segmentService.isTerminal("COMPLETED"));
    }

    @Test
    void handoverSameAndNearbyStation() {
        var ok = handoverService.canHandover(0, 3600, true, 0, true, 2, true, false, null);
        assertTrue(ok.feasible());
        assertEquals(HandoverFeasibilityService.Kind.SAME_STATION, ok.kind());

        var near = handoverService.canHandover(0, 7200, false, 80, true, 2, true, false, null);
        assertTrue(near.feasible());
        assertEquals(HandoverFeasibilityService.Kind.NEARBY_STATION, near.kind());

        var far = handoverService.canHandover(0, 7200, false, 400, true, 2, true, false, null);
        assertFalse(far.feasible());
        assertEquals(HandoverFeasibilityService.Reason.HANDOVER_DISTANCE_EXCEEDED, far.reason());
    }

    @Test
    void handoverCapacityDriverShiftAndTimeout() {
        assertFalse(handoverService.canHandover(0, 3600, true, 0, true, 0, true, false, null).feasible());
        assertFalse(handoverService.canHandover(0, 3600, true, 0, true, 2, false, false, null).feasible());
        assertFalse(handoverService.canHandover(0, 3600, true, 0, true, 2, true, true, null).feasible());
        assertFalse(handoverService.canHandover(0, 90000, true, 0, true, 2, true, false, 3600.0).feasible());
    }

    @Test
    void reachabilityCampusBlockedAndStaleUnknown() {
        var loc = new ReachabilityDecisionService.LocationFix(1L, 30.0, 104.0, 100L, 110L, 60L);
        var blocked = reachabilityService.classify(
                new ReachabilityDecisionService.AccessFlags(
                        false, false, true, true, true, false, true, true, true, true),
                loc, "重庆邮电大学校内", "NEAREST_X");
        assertEquals(ReachabilityDecisionService.Reason.VEHICLE_ACCESS_BLOCKED, blocked.reasonCode());
        assertEquals(ReachabilityDecisionService.Status.NEAREST_STATION_REQUIRED, blocked.status());
        assertEquals("重庆邮电大学校内", blocked.originalPoint());

        var stale = reachabilityService.classify(
                ReachabilityDecisionService.AccessFlags.allOpen(),
                new ReachabilityDecisionService.LocationFix(1L, 30.0, 104.0, 0L, 1000L, 60L),
                "U", "X");
        assertEquals(ReachabilityDecisionService.Status.UNKNOWN, stale.status());
        assertEquals(ReachabilityDecisionService.RecoveryAction.WAIT_LOCATION, stale.suggestedAction());
    }

    @Test
    void alreadyPassedIsNotRoadUnreachable() {
        var loc = new ReachabilityDecisionService.LocationFix(1L, 30.0, 104.0, 100L, 110L, 60L);
        var d = reachabilityService.classify(
                new ReachabilityDecisionService.AccessFlags(
                        true, true, true, true, true, true, true, true, true, true),
                loc, "U", "X");
        assertEquals(ReachabilityDecisionService.Reason.ALREADY_PASSED, d.reasonCode());
        assertNotEquals(ReachabilityDecisionService.Reason.ROAD_UNREACHABLE, d.reasonCode());
    }

    @Test
    void networkUncertainIsNotUnreachable() {
        var loc = new ReachabilityDecisionService.LocationFix(1L, 30.0, 104.0, 100L, 110L, 60L);
        var d = reachabilityService.classify(
                new ReachabilityDecisionService.AccessFlags(
                        true, true, true, true, false, false, true, true, true, true),
                loc, "U", "X");
        assertEquals(ReachabilityDecisionService.Reason.NETWORK_UNCERTAIN, d.reasonCode());
        assertEquals(ReachabilityDecisionService.Status.UNKNOWN, d.status());
    }
}
