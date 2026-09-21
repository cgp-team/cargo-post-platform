package cn.iocoder.yudao.module.transport.service.monitoring;

import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringMapDataRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringPlanRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringShiftRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringTrackRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.monitoring.vo.MonitoringVehicleRespVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 车辆监控 Service 接口
 *
 * 实时位置仅来自司机端真实上报（transport_vehicle_location），不做任何模拟插值。
 */
public interface MonitoringService {

    /**
     * 获取地图图层数据（站点 + 线路站点序列坐标）
     */
    MonitoringMapDataRespVO getMapData();

    /**
     * 获取车辆实时位置列表（真实上报；无上报则 OFFLINE 不上图）
     */
    List<MonitoringVehicleRespVO> getRealtimeVehicles();

    /**
     * 获取今日班次执行状态（真实执行记录为准，缺记录时按班次计划时间窗口推导待发/在途/已完成）
     */
    List<MonitoringShiftRespVO> getShiftExecution();

    /**
     * 获取车辆指定日期的历史轨迹（transport_vehicle_location_track，司机端在途上报）
     */
    MonitoringTrackRespVO getVehicleTrack(Long vehicleId, LocalDate date);

    /**
     * 后台调度地图：车辆完整任务段详情（Phase 11）。
     * 运营顺序来自 DispatchPlan，道路轨迹来自 RoadSegments（真实 polyline），
     * 与司机端共享同一份方案与路线。
     */
    MonitoringPlanRespVO getVehiclePlan(Long vehicleId);
}
