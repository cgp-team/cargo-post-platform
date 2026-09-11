package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键智能调度的"自动选择"规则（纯函数，可单测）。
 *
 * 产品定义：管理员点「智能调度」即可，不需要选场站/选车辆/填算法参数。
 * - 场站：由订单分布自动推导（确定性打分，见 {@link #selectDepot}）；
 * - 车辆：自动取可用车辆作为**候选池**（按运力降序、ID 升序），实际用几辆由 HACO-CPS 决定；
 * - 算法参数：缺省用项目默认值（{@link #defaultAlgorithmConfig}），走"高级设置"才允许覆盖。
 *
 * 规则全部确定性，同一批订单/车辆/站点重复运行结果一致（便于演示与回归）。
 */
@Service
public class AutoDispatchPlanner {

    /** 场站级站点（transport_station.station_level = 1：县城客运中心/青山镇站/龙泉镇站） */
    public static final int STATION_LEVEL_DEPOT = 1;
    /** 站点/车辆状态：启用/可用 */
    private static final int STATUS_ENABLED = 0;

    /**
     * 自动选择调度场站：候选为"启用且有坐标"的站点（优先 stationLevel=1 场站级；
     * 无场站级站点时退化为全部启用站点）。
     *
     * 打分：该场站到本批**所有订单取货站 + 送达站**的 Haversine 距离之和（越小越优，即总绕行最少）；
     * 同分取站点 ID 升序。无候选返回 null。
     */
    public static StationDO selectDepot(List<TransportOrderDO> orders, List<StationDO> stations) {
        if (stations == null || stations.isEmpty()) {
            return null;
        }
        List<StationDO> enabled = stations.stream()
                .filter(s -> s.getId() != null && s.getLongitude() != null && s.getLatitude() != null)
                .filter(s -> s.getStatus() == null || s.getStatus() == STATUS_ENABLED)
                // 站点启用 ≠ 可用于调度：只有 dispatchEnabled=true 的站点才能作为场站（新增站点不会自动成为场站）
                .filter(StationAccessUtil::dispatchEnabled)
                .toList();
        if (enabled.isEmpty()) {
            return null;
        }
        List<StationDO> depots = enabled.stream()
                .filter(s -> s.getStationLevel() != null && s.getStationLevel() == STATION_LEVEL_DEPOT)
                .toList();
        List<StationDO> candidates = depots.isEmpty() ? enabled : depots;
        Map<Long, StationDO> stationMap = new LinkedHashMap<>();
        enabled.forEach(s -> stationMap.put(s.getId(), s));

        return candidates.stream()
                .min(Comparator.comparingDouble((StationDO depot) -> depotScore(depot, orders, stationMap))
                        .thenComparing(StationDO::getId))
                .orElse(null);
    }

    /** 场站到本批订单各起终站的距离之和（缺坐标的站按 0 计入，不参与打分） */
    static double depotScore(StationDO depot, List<TransportOrderDO> orders, Map<Long, StationDO> stationMap) {
        if (orders == null || orders.isEmpty()) {
            return 0;
        }
        double total = 0;
        for (TransportOrderDO order : orders) {
            total += distance(depot, stationMap.get(order.getPickupStationId()));
            total += distance(depot, stationMap.get(order.getDeliveryStationId()));
        }
        return total;
    }

    private static double distance(StationDO depot, StationDO target) {
        if (target == null || target.getLongitude() == null || target.getLatitude() == null) {
            return 0;
        }
        return GeoDistanceUtil.haversineKm(depot.getLongitude().doubleValue(), depot.getLatitude().doubleValue(),
                target.getLongitude().doubleValue(), target.getLatitude().doubleValue());
    }

    /**
     * 自动选择候选车辆：可用车辆（status=0）按「货仓容量降序 → 客位降序 → ID 升序」取前 max 台。
     * 实际投入几辆由算法决定（算法可只用其中 1 台）。
     */
    public static List<VehicleDO> selectVehicles(List<VehicleDO> vehicles, int max) {
        return selectVehicles(vehicles, max, java.util.Set.of());
    }

    /**
     * 自动选择候选车辆（可选排除"已被在途方案占用"的车辆）。
     *
     * 排除原因：同一台车不能同时跑两套方案（多片区各出一套方案时，若两套都排同一台车，
     * 司机端任务会把两个片区的经停混在一起）。调用方在"全部车辆都忙"时应自行回退到不排除。
     */
    public static List<VehicleDO> selectVehicles(List<VehicleDO> vehicles, int max, java.util.Set<Long> excludedVehicleIds) {
        if (vehicles == null || vehicles.isEmpty() || max <= 0) {
            return List.of();
        }
        java.util.Set<Long> excluded = excludedVehicleIds == null ? java.util.Set.of() : excludedVehicleIds;
        return vehicles.stream()
                .filter(v -> v.getId() != null)
                .filter(v -> v.getStatus() == null || v.getStatus() == STATUS_ENABLED)
                .filter(v -> !excluded.contains(v.getId()))
                .sorted(Comparator
                        .comparingInt((VehicleDO v) -> v.getCargoCapacity() == null ? 0 : v.getCargoCapacity())
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                (VehicleDO v) -> v.getPassengerCapacity() == null ? 0 : v.getPassengerCapacity()).reversed())
                        .thenComparing(VehicleDO::getId))
                .limit(max)
                .toList();
    }

    /** 算法默认超参（与 docs/algorithm/haco-cps-design.md 一致）；调用方传入的同名键优先 */
    public static Map<String, Object> defaultAlgorithmConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("ant_count", 30);
        config.put("max_iterations", 100);
        config.put("alpha", 1.0);
        config.put("beta", 3.0);
        config.put("rho", 0.1);
        config.put("Q", 100);
        config.put("convergence_threshold", 20);
        return config;
    }

    /** 合并算法参数：默认值打底，调用方传入的同名键覆盖（高级设置） */
    public static Map<String, Object> mergeAlgorithmConfig(Map<String, Object> provided) {
        Map<String, Object> merged = defaultAlgorithmConfig();
        if (provided != null && !provided.isEmpty()) {
            provided.forEach((key, value) -> {
                if (value != null) {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    /** 同一批次的地理聚合半径(km)：超过该距离视为"另一个片区"，不混进同一次派单 */
    public static final double REGION_RADIUS_KM = 50.0;

    /**
     * 一键调度的「订单批次选择」策略（确定性，可单测）。为什么需要：订单池里可能同时存在
     * 不同片区的订单（例：重庆邮电大学片区 + 成都片区，相距 250km+）。若全部塞进同一次派单，
     * 车辆要跨城跑几百公里，算法只会判 infeasible，管理员点一次就失败。
     *
     * 策略（按优先级）：
     * 1) 订单按「创建时间倒序 → ID 倒序」，**最新那单一定优先**（演示时刚在后台审核通过的那单必进）；
     * 2) 以该单的取货/送达站为锚点，其余订单只要与锚点任一站点距离 ≤ {@link #REGION_RADIUS_KM}，
     *    视为同一片区一起纳入；跨片区的留在池里，下一次调度自动成批（形成第二套方案）；
     * 3) 单批不超过 max（算法上限之内）；订单/站点缺坐标时无法证明跨片区 → 纳入（受 max 限制）。
     *
     * @param orders     订单池订单（非空）
     * @param stationMap 站点编号 → 站点（含坐标）
     * @param max        单批最大订单数，≤0 时不限制
     * @return 本次派单使用的订单批次（非空，元素顺序＝创建时间倒序）
     */
    public static List<TransportOrderDO> selectAutoBatch(List<TransportOrderDO> orders,
                                                        Map<Long, StationDO> stationMap, int max) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<TransportOrderDO> sorted = new ArrayList<>(orders);
        sorted.sort(AutoDispatchPlanner::compareNewestFirst);
        TransportOrderDO anchor = sorted.get(0);
        int limit = max > 0 ? max : sorted.size();
        List<TransportOrderDO> batch = new ArrayList<>();
        for (TransportOrderDO order : sorted) {
            if (batch.size() >= limit) {
                break;
            }
            if (order == anchor || sameRegion(anchor, order, stationMap)) {
                batch.add(order);
            }
        }
        return batch;
    }

    /** 最新优先：创建时间倒序（空值排最后）→ ID 倒序（空值排最后）。 */
    private static int compareNewestFirst(TransportOrderDO left, TransportOrderDO right) {
        LocalDateTime leftTime = left.getCreateTime();
        LocalDateTime rightTime = right.getCreateTime();
        if (leftTime != null && rightTime != null) {
            int byTime = rightTime.compareTo(leftTime);
            if (byTime != 0) {
                return byTime;
            }
        } else if (leftTime != null) {
            return -1;
        } else if (rightTime != null) {
            return 1;
        }
        Long leftId = left.getId();
        Long rightId = right.getId();
        if (leftId == null && rightId == null) {
            return 0;
        }
        if (leftId == null) {
            return 1;
        }
        if (rightId == null) {
            return -1;
        }
        return rightId.compareTo(leftId);
    }

    /** 两单是否同片区：任一取货/送达站对的距离 ≤ REGION_RADIUS_KM；缺坐标时视为同片区（无法证伪） */
    static boolean sameRegion(TransportOrderDO anchor, TransportOrderDO order, Map<Long, StationDO> stationMap) {
        Double distance = minStationDistance(anchor, order, stationMap);
        return distance == null || distance <= REGION_RADIUS_KM;
    }

    /** 两单起终站的最小两两距离(km)；任一侧缺少可用坐标时返回 null */
    static Double minStationDistance(TransportOrderDO a, TransportOrderDO b, Map<Long, StationDO> stationMap) {
        List<StationDO> left = orderStations(a, stationMap);
        List<StationDO> right = orderStations(b, stationMap);
        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }
        Double min = null;
        for (StationDO x : left) {
            for (StationDO y : right) {
                double km = GeoDistanceUtil.haversineKm(x.getLongitude().doubleValue(), x.getLatitude().doubleValue(),
                        y.getLongitude().doubleValue(), y.getLatitude().doubleValue());
                if (min == null || km < min) {
                    min = km;
                }
            }
        }
        return min;
    }

    private static List<StationDO> orderStations(TransportOrderDO order, Map<Long, StationDO> stationMap) {
        List<StationDO> stations = new ArrayList<>(2);
        if (stationMap == null || order == null) {
            return stations;
        }
        for (Long id : new Long[]{order.getPickupStationId(), order.getDeliveryStationId()}) {
            if (id == null) {
                continue;
            }
            StationDO station = stationMap.get(id);
            if (station != null && station.getLongitude() != null && station.getLatitude() != null) {
                stations.add(station);
            }
        }
        return stations;
    }
}
