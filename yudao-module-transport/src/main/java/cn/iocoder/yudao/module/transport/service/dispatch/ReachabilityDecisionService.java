package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import org.springframework.stereotype.Service;

/**
 * 订单当前位置可达性决策（动态调度事件，不是静态拒单）。
 *
 * <p>复用 {@link StationAccessUtil} 的 userAccess / vehicleAccess / dispatchEnabled，
 * 并区分 ROAD_UNREACHABLE / ALREADY_PASSED / LOCATION_STALE 等原因。
 * UNKNOWN（定位过期、地图不确定）不得当成 UNREACHABLE。
 */
@Service
public class ReachabilityDecisionService {

    public enum Reason {
        REACHABLE,
        ROAD_UNREACHABLE,
        VEHICLE_ACCESS_BLOCKED,
        USER_POINT_UNSERVABLE,
        ALREADY_PASSED,
        ETA_MISSED,
        DETOUR_TOO_LARGE,
        DRIVER_SHIFT_CONFLICT,
        VEHICLE_CAPACITY_UNAVAILABLE,
        LOCATION_STALE,
        NETWORK_UNCERTAIN
    }

    public enum Status {
        REACHABLE,
        REACHABLE_WITH_DETOUR,
        NEAREST_STATION_REQUIRED,
        TRANSFER_REQUIRED,
        FUTURE_TRIP_REQUIRED,
        UNREACHABLE,
        UNKNOWN
    }

    public enum RecoveryAction {
        SAME_TRIP_LOCAL_REPAIR,
        SAME_ROUTE_FUTURE_TRIP,
        OTHER_ROUTE,
        MULTI_LEG,
        NEAREST_STATION,
        CUSTOMER_ACTION_REQUIRED,
        MANUAL_REVIEW,
        UNSERVICEABLE,
        WAIT_LOCATION
    }

    /** 站点静态访问结论（来自 StationAccessUtil）。 */
    public record AccessFlags(
            boolean vehicleAccess,
            boolean userAccess,
            boolean dispatchEnabled,
            boolean roadReachable,
            boolean networkKnown,
            boolean alreadyPassed,
            boolean etaFeasible,
            boolean detourFeasible,
            boolean cargoCapacityOk,
            boolean driverShiftOk) {
        public static AccessFlags allOpen() {
            return new AccessFlags(true, true, true, true, true, false, true, true, true, true);
        }
    }

    /** 车辆实时位置（来自 VehicleLocationProvider）。 */
    public record LocationFix(
            Long vehicleId,
            Double lat,
            Double lon,
            Long timestampMs,
            Long nowMs,
            long freshThresholdMs) {
        public boolean isFresh() {
            return timestampMs != null && nowMs != null
                    && (nowMs - timestampMs) <= freshThresholdMs;
        }

        public Long freshnessMs() {
            if (timestampMs == null || nowMs == null) {
                return null;
            }
            return Math.max(0L, nowMs - timestampMs);
        }
    }

    public record Decision(
            boolean reachable,
            Reason reasonCode,
            Status status,
            RecoveryAction suggestedAction,
            String servicePoint,
            String originalPoint,
            String explanation) {
    }

    /**
     * 分类当前位置 → 订单服务点可达性。
     *
     * @param originalPoint 用户原始位置（永不被覆盖）
     * @param servicePoint  实际服务点（可为最近合法站）
     */
    public Decision classify(
            AccessFlags flags,
            LocationFix location,
            String originalPoint,
            String servicePoint) {
        // 定位过期：不直接判不可达
        if (location == null || !location.isFresh()) {
            return new Decision(
                    false, Reason.LOCATION_STALE, Status.UNKNOWN, RecoveryAction.WAIT_LOCATION,
                    servicePoint, originalPoint,
                    "实时位置缺失或过期，暂不判定为不可达；保持锁定计划并等待位置刷新。");
        }
        if (!flags.networkKnown()) {
            return new Decision(
                    false, Reason.NETWORK_UNCERTAIN, Status.UNKNOWN, RecoveryAction.WAIT_LOCATION,
                    servicePoint, originalPoint,
                    "路网/地图服务暂时无法确认可达性，不作不可达判定。");
        }
        if (flags.alreadyPassed()) {
            return new Decision(
                    false, Reason.ALREADY_PASSED, Status.FUTURE_TRIP_REQUIRED,
                    RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
                    servicePoint, originalPoint,
                    "当前车辆已驶过订单位置，禁止掉头；转未来班次/其他线路/联运。");
        }
        if (!flags.roadReachable()) {
            return new Decision(
                    false, Reason.ROAD_UNREACHABLE, Status.TRANSFER_REQUIRED, RecoveryAction.MULTI_LEG,
                    servicePoint, originalPoint,
                    "当前道路网络无法到达订单位置，尝试替代道路/未来班次/其他线路/MultiLeg。");
        }
        if (!flags.vehicleAccess() || !flags.dispatchEnabled()) {
            return new Decision(
                    false, Reason.VEHICLE_ACCESS_BLOCKED, Status.NEAREST_STATION_REQUIRED,
                    RecoveryAction.NEAREST_STATION,
                    servicePoint, originalPoint,
                    "公交车辆不可进入该区域，切换最近合法服务点或重新分配承运。");
        }
        if (!flags.userAccess()) {
            return new Decision(
                    false, Reason.USER_POINT_UNSERVABLE, Status.NEAREST_STATION_REQUIRED,
                    RecoveryAction.NEAREST_STATION,
                    servicePoint, originalPoint,
                    "用户原始点不适合作为车辆服务点，保留原始位置并改派合法服务点。");
        }
        if (!flags.cargoCapacityOk()) {
            return new Decision(
                    false, Reason.VEHICLE_CAPACITY_UNAVAILABLE, Status.FUTURE_TRIP_REQUIRED,
                    RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
                    servicePoint, originalPoint,
                    "当前位置可达但车辆货运容量不足，转未来班次或其他车辆。");
        }
        if (!flags.etaFeasible()) {
            return new Decision(
                    false, Reason.ETA_MISSED, Status.FUTURE_TRIP_REQUIRED,
                    RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
                    servicePoint, originalPoint,
                    "物理可达但会破坏后续 Mandatory Stop / 班次 SLA，转未来承运。");
        }
        if (!flags.detourFeasible()) {
            return new Decision(
                    false, Reason.DETOUR_TOO_LARGE, Status.FUTURE_TRIP_REQUIRED,
                    RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
                    servicePoint, originalPoint,
                    "可达但超过当前 Trip 绕行预算，转未来班次重新获得预算。");
        }
        if (!flags.driverShiftOk()) {
            return new Decision(
                    false, Reason.DRIVER_SHIFT_CONFLICT, Status.FUTURE_TRIP_REQUIRED,
                    RecoveryAction.SAME_ROUTE_FUTURE_TRIP,
                    servicePoint, originalPoint,
                    "可达但司机/班次冲突，转未来调度。");
        }
        return new Decision(
                true, Reason.REACHABLE, Status.REACHABLE, RecoveryAction.SAME_TRIP_LOCAL_REPAIR,
                servicePoint, originalPoint,
                "当前车辆可到达合法服务点。");
    }

    /** 由 StationAccessUtil 结论组装 flags（道路/网络/已过站等由调用方补充）。 */
    public AccessFlags fromStationAccess(
            boolean userAccess, boolean vehicleAccess, boolean dispatchEnabled,
            boolean roadReachable, boolean networkKnown, boolean alreadyPassed,
            boolean etaFeasible, boolean detourFeasible,
            boolean cargoCapacityOk, boolean driverShiftOk) {
        return new AccessFlags(
                vehicleAccess, userAccess, dispatchEnabled, roadReachable, networkKnown,
                alreadyPassed, etaFeasible, detourFeasible, cargoCapacityOk, driverShiftOk);
    }
}
