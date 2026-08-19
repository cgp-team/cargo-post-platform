package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringTrackRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 车辆监控 Service 接口
 *
 * 当前无 GPS 设备接入、调度闭环未实现，实时位置按"班次计划时间 + 线路站点序列"模拟插值。
 * 调度闭环（transport_dispatch_plan_item）落地后可切换数据源，接口保持不变。
 */
public interface MonitoringService {

    /**
     * 获取地图图层数据（站点 + 线路站点序列坐标）
     */
    MonitoringMapDataRespVO getMapData();

    /**
     * 获取车辆实时模拟位置列表
     */
    List<MonitoringVehicleRespVO> getRealtimeVehicles();

    /**
     * 获取今日班次执行状态（按班次计划时间窗口模拟）
     */
    List<MonitoringShiftRespVO> getShiftExecution();

    /**
     * 获取车辆指定日期的历史轨迹（transport_vehicle_location_track，司机端在途上报）
     */
    MonitoringTrackRespVO getVehicleTrack(Long vehicleId, LocalDate date);
}
