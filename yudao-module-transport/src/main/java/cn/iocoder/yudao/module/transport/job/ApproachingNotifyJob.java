package cn.iocoder.yudao.module.transport.job;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleLocationMapper;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverAppService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 到站提醒兜底 Job：按车辆最后已知位置补发"即将送达"提醒。
 *
 * 断流背景：提醒原先只在 {@code reportLocation} 里触发，而司机端一切后台
 * （小程序 onHide 会清掉定位定时器）位置上报就停了——车辆明明已经开到站点附近，
 * 用户却收不到提醒。本 Job 用车辆最后一次上报的位置兜底计算，补上这段空窗。
 *
 * 幂等：{@code DriverAppService#notifyApproachingOrders} 以
 * 「订单 + 距离档位」为幂等键（CARRIER_APPROACHING:ORDER-xx:STAGE-yy），
 * 重复执行不会重复打扰用户，因此本 Job 可以高频执行。
 *
 * 生效方式：管理端「定时任务」页注册本处理器（建议每分钟一次）。
 */
@Component
@Slf4j
public class ApproachingNotifyJob implements JobHandler {

    /**
     * 只看这段时间内有位置上报的车辆。
     * 更旧的位置说明车辆早已离线/任务结束，再按它算距离会给出错误的"即将送达"。
     */
    private static final int LOCATION_FRESH_MINUTES = 30;

    @Resource
    private VehicleLocationMapper vehicleLocationMapper;
    @Resource
    private DriverAppService driverAppService;

    @Override
    @TenantIgnore
    public String execute(String param) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(LOCATION_FRESH_MINUTES);
        List<VehicleLocationDO> locations = vehicleLocationMapper.selectList(
                new LambdaQueryWrapperX<VehicleLocationDO>()
                        .ge(VehicleLocationDO::getReportTime, since));

        int scanned = 0;
        int failed = 0;
        for (VehicleLocationDO loc : locations) {
            if (loc.getVehicleId() == null || loc.getLongitude() == null || loc.getLatitude() == null) {
                continue; // 缺坐标无法算距离
            }
            scanned++;
            try {
                driverAppService.notifyApproachingOrders(loc.getVehicleId(),
                        loc.getLongitude().doubleValue(), loc.getLatitude().doubleValue());
            } catch (Exception ex) {
                // 单车失败不影响其余车辆
                failed++;
                log.warn("[execute][到站提醒兜底失败 vehicleId={}：{}]", loc.getVehicleId(), ex.getMessage());
            }
        }
        return String.format("到站提醒兜底：近 %s 分钟有位置的车辆 %s 辆，处理失败 %s 辆",
                LOCATION_FRESH_MINUTES, scanned, failed);
    }
}
