package cn.iocoder.yudao.module.transport.service.transport.bus;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import cn.iocoder.yudao.module.transport.service.monitoring.MonitoringService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 小程序实时公交 Service 实现。
 * 车辆位置直接复用 {@link MonitoringService#getRealtimeVehicles()}（司机上报 5 分钟优先，否则时刻插值），
 * 线路起终点取自 {@link MonitoringService#getMapData()} 的线路站点序列，ETA 由进度与班次计划时长估算。
 */
@Service
@Validated
public class AppBusServiceImpl implements AppBusService {

    @Resource private MonitoringService monitoringService;
    @Resource private ShiftMapper shiftMapper;

    @Override
    public List<AppBusRespVO> getRealtimeBuses() {
        List<MonitoringVehicleRespVO> vehicles = monitoringService.getRealtimeVehicles();
        MonitoringMapDataRespVO mapData = monitoringService.getMapData();
        // 线路名称 → 线路（取起点/终点站名）
        Map<String, MonitoringMapDataRespVO.Route> routeByName = mapData.getRoutes() == null ? Map.of()
                : mapData.getRoutes().stream().collect(Collectors.toMap(
                        MonitoringMapDataRespVO.Route::getRouteName, Function.identity(), (a, b) -> a));
        // 班次编码 → 计划时长（ETA 估算）
        Map<String, Integer> durationByShiftCode = shiftMapper.selectList().stream()
                .collect(Collectors.toMap(ShiftDO::getShiftCode,
                        s -> s.getPlannedDurationMinutes() != null ? s.getPlannedDurationMinutes() : 60,
                        (a, b) -> a));
        return vehicles.stream()
                // 只有分配到班次的车辆才进入公交列表（在途行驶中 / 空闲停靠起点）
                .filter(v -> v.getShiftCode() != null)
                .map(v -> {
                    AppBusRespVO vo = new AppBusRespVO();
                    vo.setBusId(v.getVehicleId());
                    vo.setPlateNo(v.getPlateNo());
                    vo.setShiftCode(v.getShiftCode());
                    vo.setRouteName(v.getRouteName());
                    MonitoringMapDataRespVO.Route route = routeByName.get(v.getRouteName());
                    if (route != null && route.getPoints() != null && !route.getPoints().isEmpty()) {
                        vo.setStartStation(route.getPoints().get(0).getStationName());
                        vo.setEndStation(route.getPoints().get(route.getPoints().size() - 1).getStationName());
                    }
                    vo.setStatus(v.getStatus());
                    vo.setNextStation(v.getNextStationName());
                    vo.setProgress(v.getProgress());
                    vo.setSpeedKmh(v.getSpeedKmh());
                    // ETA = 剩余进度占比 × 班次计划时长（向下取整至少 1 分钟）
                    int duration = durationByShiftCode.getOrDefault(v.getShiftCode(), 60);
                    int progress = v.getProgress() != null ? v.getProgress() : 0;
                    vo.setEtaMinutes(Math.max(1, Math.round((100 - progress) / 100.0f * duration)));
                    return vo;
                }).toList();
    }

}
