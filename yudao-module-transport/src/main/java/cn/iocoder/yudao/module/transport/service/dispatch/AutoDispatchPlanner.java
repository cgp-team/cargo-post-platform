package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * 按「运营线路覆盖」挑候选车辆（业务默认：**一条真实线路只跑一辆公交车**）。
     *
     * <p>为什么不用"运力排序"挑车：一键调度要的是"这批订单落在哪几条线路上，就让那几条线路的车来跑"。
     * 按运力挑车会把 347 路的车派去送 320 路的货，司机端任务串线、可视化也乱。
     * 这里先用订单站点和每条线路的站点求交集（覆盖分），再按覆盖分挑车；</p>
     *
     * <p>一条线路只出一辆车（同线路多台绑定取 ID 最小者，保证结果确定）；已开过的站不在
     * 「剩余行程」里的车会被 {@link #selectVehiclesByLineCoverage} 的调用方另行过滤（不折返）。</p>
     *
     * @param orders            本批订单（取送站用于算覆盖分）
     * @param vehicles          可用车辆（已按状态过滤）
     * @param bindings          有效人车绑定（含运营线路 routeId）
     * @param routeStationIds   运营线路编号 → 该线路站点编号列表
     * @param max               最多返回几台
     * @param excludedVehicleIds 需要避让的车辆（已在途方案占用的车）
     * @return 覆盖分 > 0 的候选车（按覆盖分降序、运力降序、ID 升序）；没有覆盖本批站点的线路时返回空
     */
    public static List<VehicleDO> selectVehiclesByLineCoverage(
            List<TransportOrderDO> orders,
            List<VehicleDO> vehicles,
            List<DriverVehicleDO> bindings,
            Map<Long, List<Long>> routeStationIds,
            int max,
            Set<Long> excludedVehicleIds) {
        if (vehicles == null || vehicles.isEmpty() || max <= 0 || bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, VehicleDO> vehicleMap = vehicles.stream()
                .filter(v -> v.getId() != null)
                .filter(v -> v.getStatus() == null || v.getStatus() == STATUS_ENABLED)
                .collect(Collectors.toMap(VehicleDO::getId, v -> v, (a, b) -> a));
        if (vehicleMap.isEmpty()) {
            return List.of();
        }
        Set<Long> wantedStations = new HashSet<>();
        if (orders != null) {
            orders.forEach(order -> {
                if (order != null && order.getPickupStationId() != null) {
                    wantedStations.add(order.getPickupStationId());
                }
                if (order != null && order.getDeliveryStationId() != null) {
                    wantedStations.add(order.getDeliveryStationId());
                }
            });
        }
        if (wantedStations.isEmpty()) {
            return List.of();
        }

        // 一条线路一辆车：同一 routeId 只保留车辆 ID 最小的那台
        Map<Long, DriverVehicleDO> oneVehiclePerRoute = new LinkedHashMap<>();
        for (DriverVehicleDO binding : bindings) {
            if (binding == null || binding.getRouteId() == null || binding.getVehicleId() == null) {
                continue;
            }
            if (!vehicleMap.containsKey(binding.getVehicleId())) {
                continue;
            }
            oneVehiclePerRoute.merge(binding.getRouteId(), binding,
                    (a, b) -> a.getVehicleId() <= b.getVehicleId() ? a : b);
        }

        List<VehicleLineScore> scored = new ArrayList<>();
        oneVehiclePerRoute.forEach((routeId, binding) -> {
            Set<Long> lineStations = new HashSet<>(routeStationIds.getOrDefault(routeId, List.of()));
            int covered = 0;
            for (Long stationId : wantedStations) {
                if (lineStations.contains(stationId)) {
                    covered++;
                }
            }
            scored.add(new VehicleLineScore(vehicleMap.get(binding.getVehicleId()), binding.getRouteId(), covered));
        });

        Set<Long> excluded = excludedVehicleIds == null ? Set.of() : excludedVehicleIds;
        List<VehicleDO> picked = new ArrayList<>();
        scored.sort(Comparator.comparingInt(VehicleLineScore::coveredStations).reversed()
                .thenComparing(Comparator.comparingInt(
                        (VehicleLineScore s) -> s.vehicle().getCargoCapacity() == null ? 0 : s.vehicle().getCargoCapacity()).reversed())
                .thenComparing(Comparator.comparingInt(
                        (VehicleLineScore s) -> s.vehicle().getPassengerCapacity() == null ? 0 : s.vehicle().getPassengerCapacity()).reversed())
                .thenComparing(s -> s.vehicle().getId()));
        for (VehicleLineScore score : scored) {
            if (picked.size() >= max) {
                break;
            }
            if (score.coveredStations() <= 0) {
                break; // 不覆盖本批站点的线路不参与（宁可退回运力兜底，也不乱派）
            }
            if (excluded.contains(score.vehicle().getId())) {
                continue;
            }
            picked.add(score.vehicle());
        }
        return picked;
    }

    /** 候选车与线路的覆盖分（内部排序用） */
    private record VehicleLineScore(VehicleDO vehicle, Long routeId, int coveredStations) {
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
        return selectAutoBatch(orders, stationMap, max, Integer.MAX_VALUE);
    }

    /**
     * 一键调度的「订单批次选择」（带**站点预算**）。
     *
     * <p>为什么需要站点预算：算法契约上限是"30 站点 / 25 订单 / 3 车"。只按订单数取批时，
     * 一批 25 单跨多条线路很容易凑出 40~50 个不同站点 → `validateScaleLimit` 直接抛
     * DISPATCH_SCALE_OVER_LIMIT，现场表现就是"点了一键调度，没有方案"。
     * 这里在按片区纳入订单的同时累计站点数，超出预算的订单留给下一批（下一轮一键调度会成下一套方案），
     * 保证每一批都在算法可解规模内。</p>
     *
     * @param maxStations 本批允许的最大站点数（不含场站；调用方传算法上限-1）
     */
    public static List<TransportOrderDO> selectAutoBatch(List<TransportOrderDO> orders,
                                                        Map<Long, StationDO> stationMap, int max,
                                                        int maxStations) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        List<TransportOrderDO> sorted = new ArrayList<>(orders);
        sorted.sort(AutoDispatchPlanner::compareNewestFirst);
        TransportOrderDO anchor = sorted.get(0);
        int limit = max > 0 ? max : sorted.size();
        int stationBudget = maxStations > 0 ? maxStations : Integer.MAX_VALUE;
        List<TransportOrderDO> batch = new ArrayList<>();
        Set<Long> batchStations = new HashSet<>();
        for (TransportOrderDO order : sorted) {
            if (batch.size() >= limit) {
                break;
            }
            if (order != anchor && !sameRegion(anchor, order, stationMap)) {
                continue;
            }
            // 站点预算：本单带来的新站点会让本批超限 → 留给下一批（锚点那单必须进批）
            Set<Long> candidate = new HashSet<>(batchStations);
            if (order.getPickupStationId() != null) {
                candidate.add(order.getPickupStationId());
            }
            if (order.getDeliveryStationId() != null) {
                candidate.add(order.getDeliveryStationId());
            }
            if (order != anchor && candidate.size() > stationBudget) {
                continue;
            }
            batch.add(order);
            batchStations = candidate;
        }
        return batch;
    }

    /**
     * 运力预算：候选车队里"最大 {@code maxVehicles} 台车"的货仓件数合计。
     *
     * <p>算法预检口径（`hybrid_optimizer._precheck`）是：
     * {@code pickups ≤ Σ cargoCapacity} 且 {@code deliveries ≤ Σ cargoCapacity}，
     * 超出直接返回 OVER_CAPACITY（前端文案"运力不足（订单总需求超出可用车辆总容量）"）。
     * 因此取批时按这个上限带走货量，超出的订单留给下一批（下一轮一键调度成下一套方案）。</p>
     *
     * @param vehicles       可用车辆
     * @param maxVehicles    本次最多用几台车（与 MAX_ALGORITHM_VEHICLES 一致）
     * @param fallbackCapacity 车辆档案缺货仓件数时的默认值（与算法契约默认 4 一致）
     */
    public static int capacityBudget(List<VehicleDO> vehicles, int maxVehicles, int fallbackCapacity) {
        if (vehicles == null || vehicles.isEmpty() || maxVehicles <= 0) {
            return 0;
        }
        return vehicles.stream()
                .filter(v -> v.getId() != null)
                .filter(v -> v.getStatus() == null || v.getStatus() == STATUS_ENABLED)
                .map(v -> v.getCargoCapacity() == null ? fallbackCapacity : v.getCargoCapacity())
                .sorted(Comparator.reverseOrder())
                .limit(maxVehicles)
                .mapToInt(Integer::intValue)
                .sum();
    }

    /**
     * 按"件数预算"裁剪批次：保持传入顺序（调用方已按最新优先排好），
     * 逐单累加货量，超过预算的订单留给下一批（不丢单，下一轮会再成方案）。
     * 首单（锚点）无条件保留，避免出现空批次。
     */
    public static List<TransportOrderDO> capByTotalItems(List<TransportOrderDO> orders,
                                                         Map<Long, Integer> itemsByOrderId,
                                                         int maxItems) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        if (maxItems <= 0) {
            return List.copyOf(orders);
        }
        List<TransportOrderDO> kept = new ArrayList<>();
        int total = 0;
        for (TransportOrderDO order : orders) {
            int items = 0;
            if (order.getId() != null && itemsByOrderId != null) {
                items = itemsByOrderId.getOrDefault(order.getId(), 1);
            }
            if (!kept.isEmpty() && total + items > maxItems) {
                continue; // 本单放不下 → 留给下一批
            }
            kept.add(order);
            total += items;
        }
        return kept;
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
