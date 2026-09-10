package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import org.springframework.stereotype.Service;

import java.util.Comparator;
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
        if (vehicles == null || vehicles.isEmpty() || max <= 0) {
            return List.of();
        }
        return vehicles.stream()
                .filter(v -> v.getId() != null)
                .filter(v -> v.getStatus() == null || v.getStatus() == STATUS_ENABLED)
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
}
