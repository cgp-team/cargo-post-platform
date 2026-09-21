package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 统一车辆位置提供者（Read Model）。
 *
 * 数据来源优先级：REAL > OFFLINE
 * - REAL：司机端 GPS 上报（transport_vehicle_location，15 分钟内有效）
 * - OFFLINE：真正没有位置（无上报）
 *
 * 监控页面应通过此服务获取车辆位置，而非直接读取各数据源。
 */
@Service
public class VehicleLocationProvider {

    /** 真实位置有效窗口（分钟） */
    private static final int REAL_LOCATION_VALID_MINUTES = 15;
    /** 真实位置新鲜阈值（分钟）：低于此值为 REAL_FRESH */
    private static final int REAL_FRESH_MINUTES = 5;

    @Resource private VehicleLocationMapper vehicleLocationMapper;

    /**
     * 获取单个车辆的位置快照（REAL > OFFLINE）
     */
    public VehicleLocationSnapshot getLocation(Long vehicleId) {
        // 1. 尝试 REAL
        VehicleLocationDO realLocation = vehicleLocationMapper.selectByVehicleId(vehicleId);
        if (realLocation != null && realLocation.getLongitude() != null
                && realLocation.getReportTime() != null
                && realLocation.getReportTime().isAfter(LocalDateTime.now().minusMinutes(REAL_LOCATION_VALID_MINUTES))) {
            return buildRealSnapshot(vehicleId, realLocation);
        }

        // 2. OFFLINE
        return VehicleLocationSnapshot.builder()
                .vehicleId(vehicleId)
                .source("OFFLINE")
                .status(0) // IDLE
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 批量获取车辆位置快照（优化：批量查询真实位置，减少 DB 访问）
     */
    public Map<Long, VehicleLocationSnapshot> getLocations(Set<Long> vehicleIds) {
        if (vehicleIds == null || vehicleIds.isEmpty()) {
            return Map.of();
        }

        // 批量查询真实位置
        Map<Long, VehicleLocationDO> realLocationMap = vehicleLocationMapper
                .selectRecent(LocalDateTime.now().minusMinutes(REAL_LOCATION_VALID_MINUTES))
                .stream()
                .collect(Collectors.toMap(VehicleLocationDO::getVehicleId, Function.identity(), (a, b) -> a));

        return vehicleIds.stream().collect(Collectors.toMap(
                Function.identity(),
                vehicleId -> {
                    // REAL 优先
                    VehicleLocationDO realLocation = realLocationMap.get(vehicleId);
                    if (realLocation != null && realLocation.getLongitude() != null) {
                        return buildRealSnapshot(vehicleId, realLocation);
                    }
                    // OFFLINE
                    return VehicleLocationSnapshot.builder()
                            .vehicleId(vehicleId)
                            .source("OFFLINE")
                            .status(0)
                            .updatedAt(LocalDateTime.now())
                            .build();
                }
        ));
    }

    private VehicleLocationSnapshot buildRealSnapshot(Long vehicleId, VehicleLocationDO loc) {
        boolean fresh = loc.getReportTime() != null
                && loc.getReportTime().isAfter(LocalDateTime.now().minusMinutes(REAL_FRESH_MINUTES));
        return VehicleLocationSnapshot.builder()
                .vehicleId(vehicleId)
                .longitude(toDouble(loc.getLongitude()))
                .latitude(toDouble(loc.getLatitude()))
                .speedKmh(toDouble(loc.getSpeedKmh()))
                .source(fresh ? "REAL" : "REAL_STALE")
                .status(1) // IN_TRANSIT
                .updatedAt(loc.getReportTime())
                .build();
    }

    private static Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }
}
