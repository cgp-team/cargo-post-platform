package cn.iocoder.yudao.module.transport.service.transport.send;

import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendReachabilityRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.integration.algorithm.AlgorithmClient;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistancePairDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceReqDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmDistanceRespDTO;
import cn.iocoder.yudao.module.transport.integration.algorithm.dto.AlgorithmStationDTO;
import cn.iocoder.yudao.module.transport.enums.order.ReviewReasonCodeEnum;
import cn.iocoder.yudao.module.transport.enums.order.ServiceModeEnum;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import cn.iocoder.yudao.module.transport.util.StationAccessUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 寄货前"当前位置可达性"评估（核心业务：位置 ≠ 车辆能到的地方）。
 *
 * 规则（确定性，便于答辩演示与回归）：
 * 1. 候选站点 = 启用且有坐标的项目站点；
 * 2. 按到用户位置的距离升序排序（同距取 stationId 升序）；
 * 3. 对最近站点优先取**高德道路距离**，不可用则回退 **Haversine 直线距离**并标注 provider；
 * 4. 距离 ≤ {@link #REACHABLE_RADIUS_KM} km → 可就近服务（DOOR_PICKUP）；
 *    否则判定"车辆无法直接进入"（USER_LOCATION_UNREACHABLE）→ 推荐该站点，提示用户送站（NEAREST_STATION）。
 *
 * 说明：真实"校园禁行区"依赖地图围栏数据，这里用"到最近可服务站点距离"作为确定性代理，
 * 换城市/换站点数据都不需要改代码（不硬编码任何具体地点）。
 */
@Service
@Slf4j
public class AppSendReachabilityService {

    /** 车辆可直接服务的最大距离（km）：超过即建议用户送到最近站点 */
    public static final double REACHABLE_RADIUS_KM = 0.3;
    /** 步行速度（m/s）：约 4.3 km/h */
    private static final double WALK_SPEED_MPS = 1.2;
    /** 站点状态：启用 */
    private static final int STATUS_ENABLED = 0;

    @Resource private StationMapper stationMapper;
    @Resource private AlgorithmClient algorithmClient;

    public AppSendReachabilityRespVO evaluate(Double latitude, Double longitude) {
        AppSendReachabilityRespVO resp = new AppSendReachabilityRespVO();
        if (latitude == null || longitude == null) {
            resp.setReachable(false);
            resp.setReasonCode(ReviewReasonCodeEnum.ROAD_UNREACHABLE.getCode());
            resp.setMessage("未提供当前位置，无法判断可达性");
            return resp;
        }
        // 候选站点：启用 + 用户可达（用户能把货送到/取到的地方）
        List<StationDO> candidates = stationMapper.selectList().stream()
                .filter(s -> s.getId() != null && s.getLongitude() != null && s.getLatitude() != null)
                .filter(StationAccessUtil::userAccessible)
                .sorted(Comparator.comparingDouble((StationDO s) -> GeoDistanceUtil.haversineKm(
                                longitude, latitude,
                                s.getLongitude().doubleValue(), s.getLatitude().doubleValue()))
                        .thenComparing(StationDO::getId))
                .toList();
        if (candidates.isEmpty()) {
            resp.setReachable(false);
            resp.setReasonCode(ReviewReasonCodeEnum.NO_SAFE_HANDOFF_POINT.getCode());
            resp.setMessage("附近暂无可用服务站点，请联系平台");
            return resp;
        }
        StationDO nearestUserStation = candidates.get(0);
        double straightKm = GeoDistanceUtil.haversineKm(longitude, latitude,
                nearestUserStation.getLongitude().doubleValue(), nearestUserStation.getLatitude().doubleValue());
        // 车辆可达性判定（核心原则：站点启用 ≠ 车辆能进；校园禁行区站点 vehicleAccess=false）：
        // 若最近站点就在身边且车辆可进 → 可就近服务；否则推荐"最近的可服务（车辆可达）站点"让用户送站。
        boolean doorService = straightKm <= REACHABLE_RADIUS_KM
                && StationAccessUtil.vehicleAccessible(nearestUserStation);
        StationDO nearest;
        if (doorService) {
            nearest = nearestUserStation;
        } else {
            nearest = stationMapper.selectList().stream()
                    .filter(s -> s.getId() != null && s.getLongitude() != null && s.getLatitude() != null)
                    .filter(StationAccessUtil::vehicleAccessible)
                    .min(Comparator.comparingDouble((StationDO s) -> GeoDistanceUtil.haversineKm(
                                    longitude, latitude,
                                    s.getLongitude().doubleValue(), s.getLatitude().doubleValue()))
                            .thenComparing(StationDO::getId))
                    .orElse(null);
            if (nearest == null) {
                resp.setReachable(false);
                resp.setReasonCode(ReviewReasonCodeEnum.NO_SAFE_HANDOFF_POINT.getCode());
                resp.setMessage("附近暂无可供车辆停靠的服务站点，请联系平台");
                return resp;
            }
        }
        double recommendStraightKm = GeoDistanceUtil.haversineKm(longitude, latitude,
                nearest.getLongitude().doubleValue(), nearest.getLatitude().doubleValue());
        double km = straightKm;
        String provider = "HAVERSINE";
        if (!doorService) {
            km = recommendStraightKm; // 推荐站点距离决定步行时间
        }
        // 道路距离优先（高德）：失败/不可用不影响主流程，退回直线并如实标注
        try {
            AlgorithmDistanceRespDTO dto = algorithmClient.distance(AlgorithmDistanceReqDTO.builder()
                    .stations(List.of(
                            AlgorithmStationDTO.builder().stationId("USER")
                                    .longitude(longitude).latitude(latitude).build(),
                            AlgorithmStationDTO.builder().stationId(String.valueOf(nearest.getId()))
                                    .longitude(nearest.getLongitude().doubleValue())
                                    .latitude(nearest.getLatitude().doubleValue()).build()))
                    .build());
            if (dto != null && dto.getPairs() != null && !dto.getPairs().isEmpty()) {
                AlgorithmDistancePairDTO pair = dto.getPairs().get(0);
                if (Boolean.TRUE.equals(pair.getAvailable()) && pair.getDistanceKm() != null) {
                    km = pair.getDistanceKm();
                    provider = "amap".equals(pair.getProvider()) ? "AMAP_ROAD" : "EUCLIDEAN";
                }
            }
        } catch (Exception ex) {
            log.debug("[reachability] 道路距离不可用，使用直线估算：{}", ex.getMessage());
        }

        boolean reachable = km <= REACHABLE_RADIUS_KM;
        AppSendReachabilityRespVO.RecommendedStation station = new AppSendReachabilityRespVO.RecommendedStation();
        station.setId(nearest.getId());
        station.setName(nearest.getStationName());
        station.setLongitude(nearest.getLongitude().doubleValue());
        station.setLatitude(nearest.getLatitude().doubleValue());
        station.setDistanceKm(round2(km));

        resp.setReachable(reachable);
        resp.setReasonCode(reachable ? null : ReviewReasonCodeEnum.USER_LOCATION_UNREACHABLE.getCode());
        resp.setServiceMode(reachable ? ServiceModeEnum.DOOR_PICKUP.getCode()
                : ServiceModeEnum.NEAREST_STATION.getCode());
        resp.setDistanceKm(round2(km));
        resp.setWalkMinutes(walkMinutes(km));
        resp.setDistanceProvider(provider);
        resp.setRecommendedStation(station);
        resp.setMessage(reachable
                ? "当前位置可由车辆就近服务，取货站将使用最近站点"
                : "当前位置车辆无法直接进入，请送到最近服务站点交接");
        return resp;
    }

    /** 步行分钟（向上取整，最少 1 分钟） */
    static int walkMinutes(double km) {
        return Math.max(1, (int) Math.ceil(km * 1000 / WALK_SPEED_MPS / 60));
    }

    private static BigDecimal round2(double km) {
        return BigDecimal.valueOf(Math.round(km * 100) / 100.0);
    }
}
