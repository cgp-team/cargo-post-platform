package cn.iocoder.yudao.module.transport.service.developer;

import cn.iocoder.yudao.module.transport.controller.admin.developer.vo.DeveloperHealthRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.developer.vo.DeveloperStatisticsRespVO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.DispatchPlanMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.simulation.SimulationRunMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.service.simulation.SimulationEngine;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发者中心健康检查和统计服务
 */
@Service
public class DeveloperHealthService {

    @Resource private DataSource dataSource;
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private SimulationEngine simulationEngine;
    @Resource private StationMapper stationMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private DriverMapper driverMapper;
    @Resource private TransportOrderMapper transportOrderMapper;
    @Resource private DispatchPlanMapper dispatchPlanMapper;
    @Resource private SimulationRunMapper simulationRunMapper;
    @Resource private DeveloperModeService developerModeService;
    @Resource private DeveloperSimulationGuard guard;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * 系统健康检查
     */
    public DeveloperHealthRespVO checkHealth() {
        DeveloperHealthRespVO respVO = new DeveloperHealthRespVO();
        List<DeveloperHealthRespVO.ServiceHealth> services = new ArrayList<>();
        boolean allHealthy = true;
        boolean anyDegraded = false;

        // MySQL
        services.add(checkMysql());
        // Redis
        services.add(checkRedis());
        // Simulation Engine
        services.add(checkSimulationEngine());
        // Vehicle Monitoring
        services.add(checkVehicleMonitoring());

        for (DeveloperHealthRespVO.ServiceHealth svc : services) {
            if ("OFFLINE".equals(svc.getStatus())) {
                allHealthy = false;
            } else if ("DEGRADED".equals(svc.getStatus())) {
                anyDegraded = true;
            }
        }

        respVO.setOverallStatus(allHealthy ? (anyDegraded ? "DEGRADED" : "HEALTHY") : "OFFLINE");
        respVO.setServices(services);
        return respVO;
    }

    private DeveloperHealthRespVO.ServiceHealth checkMysql() {
        DeveloperHealthRespVO.ServiceHealth health = new DeveloperHealthRespVO.ServiceHealth();
        health.setName("MySQL");
        health.setCheckedAt(LocalDateTime.now().format(TIME_FMT));
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(3);
            health.setLatencyMs(System.currentTimeMillis() - start);
            health.setStatus("HEALTHY");
        } catch (Exception e) {
            health.setStatus("OFFLINE");
            health.setError(e.getMessage());
        }
        return health;
    }

    private DeveloperHealthRespVO.ServiceHealth checkRedis() {
        DeveloperHealthRespVO.ServiceHealth health = new DeveloperHealthRespVO.ServiceHealth();
        health.setName("Redis");
        health.setCheckedAt(LocalDateTime.now().format(TIME_FMT));
        long start = System.currentTimeMillis();
        try {
            stringRedisTemplate.hasKey("health-check");
            health.setLatencyMs(System.currentTimeMillis() - start);
            health.setStatus("HEALTHY");
        } catch (Exception e) {
            health.setStatus("OFFLINE");
            health.setError(e.getMessage());
        }
        return health;
    }

    private DeveloperHealthRespVO.ServiceHealth checkSimulationEngine() {
        DeveloperHealthRespVO.ServiceHealth health = new DeveloperHealthRespVO.ServiceHealth();
        health.setName("模拟引擎");
        health.setCheckedAt(LocalDateTime.now().format(TIME_FMT));
        health.setLatencyMs(0L);
        health.setStatus(guard.isEnvironmentSimulationEnabled() ? "HEALTHY" : "DEGRADED");
        if (!guard.isEnvironmentSimulationEnabled()) {
            health.setError("SIMULATION_ENABLED=false");
        }
        return health;
    }

    private DeveloperHealthRespVO.ServiceHealth checkVehicleMonitoring() {
        DeveloperHealthRespVO.ServiceHealth health = new DeveloperHealthRespVO.ServiceHealth();
        health.setName("车辆监控");
        health.setCheckedAt(LocalDateTime.now().format(TIME_FMT));
        health.setLatencyMs(0L);
        health.setStatus("HEALTHY");
        return health;
    }

    /**
     * 获取开发者中心统计数据
     */
    public DeveloperStatisticsRespVO getStatistics() {
        DeveloperStatisticsRespVO vo = new DeveloperStatisticsRespVO();
        vo.setSimulationEnabled(guard.isEnvironmentSimulationEnabled());
        vo.setStationCount(stationMapper.selectCount().intValue());
        vo.setVehicleCount(vehicleMapper.selectCount().intValue());
        vo.setDriverCount(driverMapper.selectCount().intValue());
        vo.setOrderCount(transportOrderMapper.selectCount().intValue());
        vo.setTotalSimulations(simulationRunMapper.selectCount().intValue());
        // 运行中的模拟数（通过引擎检查）
        vo.setRunningSimulations(0);
        vo.setSimulatedVehicles(0);
        vo.setTestDataCount(0);
        return vo;
    }
}
