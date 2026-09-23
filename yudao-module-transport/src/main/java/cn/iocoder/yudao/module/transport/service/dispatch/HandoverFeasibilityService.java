package cn.iocoder.yudao.module.transport.service.dispatch;

import org.springframework.stereotype.Service;

/**
 * 联运交接可行性（算法层 canHandover 的后端裁决）。
 *
 * <p>同站 handoverDistance=0；近站超阈值 INFEASIBLE。
 * 不把“有公交线路关系”直接等价成“可以交接”。
 */
@Service
public class HandoverFeasibilityService {

    public enum Kind { SAME_STATION, NEARBY_STATION }

    public enum Reason {
        HANDOVER_OK,
        CARGO_NOT_PRESENT,
        NEXT_CAPACITY_UNAVAILABLE,
        DRIVER_UNAVAILABLE,
        SHIFT_CONFLICT,
        HANDOVER_DISTANCE_EXCEEDED,
        WAIT_TIMEOUT,
        NEXT_TRIP_DEPARTED
    }

    public record Decision(boolean feasible, Kind kind, Reason reason, double transferDistanceM,
                           double handlingTimeS, double dwellTimeS, double waitingTimeS,
                           double operationalCost, double failureRisk) {
        public double totalCost() {
            return operationalCost + waitingTimeS / 60.0 + handlingTimeS / 60.0 + dwellTimeS / 60.0 + failureRisk;
        }
    }

    /** 近站交接最大步行距离（米），与 MultiLeg 约 150m 网格语义对齐。 */
    public static final double NEARBY_THRESHOLD_M = 150.0;
    public static final double DEFAULT_DWELL_S = 20 * 60.0;
    public static final double DEFAULT_HANDLING_S = 300.0;

    public Decision canHandover(
            double fromArrivalTimeS,
            double toDepartureTimeS,
            boolean sameStation,
            double stationDistanceM,
            boolean cargoPresent,
            int nextCargoCapacity,
            boolean nextDriverAvailable,
            boolean shiftConflict,
            Double orderWaitLimitS) {
        if (!cargoPresent) {
            return new Decision(false, null, Reason.CARGO_NOT_PRESENT, 0, 0, 0, 0, 0, 0);
        }
        if (nextCargoCapacity <= 0) {
            return new Decision(false, null, Reason.NEXT_CAPACITY_UNAVAILABLE, 0, 0, 0, 0, 0, 0);
        }
        if (!nextDriverAvailable) {
            return new Decision(false, null, Reason.DRIVER_UNAVAILABLE, 0, 0, 0, 0, 0, 0);
        }
        if (shiftConflict) {
            return new Decision(false, null, Reason.SHIFT_CONFLICT, 0, 0, 0, 0, 0, 0);
        }
        if (!sameStation && stationDistanceM > NEARBY_THRESHOLD_M) {
            return new Decision(false, Kind.NEARBY_STATION, Reason.HANDOVER_DISTANCE_EXCEEDED,
                    stationDistanceM, 0, 0, 0, 0, 0);
        }
        Kind kind = sameStation ? Kind.SAME_STATION : Kind.NEARBY_STATION;
        double transferD = sameStation ? 0.0 : Math.max(0.0, stationDistanceM);
        double travel = transferD / 1.2; // 步行约 1.2 m/s
        double waiting = Math.max(0.0, toDepartureTimeS - (fromArrivalTimeS + DEFAULT_DWELL_S + travel));
        if (orderWaitLimitS != null
                && Math.max(0.0, toDepartureTimeS - fromArrivalTimeS) > orderWaitLimitS) {
            return new Decision(false, kind, Reason.WAIT_TIMEOUT, transferD, DEFAULT_HANDLING_S,
                    DEFAULT_DWELL_S, waiting, 0, 0);
        }
        if (toDepartureTimeS + 1e-6 < fromArrivalTimeS) {
            return new Decision(false, kind, Reason.NEXT_TRIP_DEPARTED, transferD, DEFAULT_HANDLING_S,
                    DEFAULT_DWELL_S, 0, 0, 0);
        }
        double opCost = 2.0 + transferD / 1000.0;
        double risk = sameStation ? 0.0 : 0.2;
        return new Decision(true, kind, Reason.HANDOVER_OK, transferD, DEFAULT_HANDLING_S,
                DEFAULT_DWELL_S, waiting, opCost, risk);
    }
}
