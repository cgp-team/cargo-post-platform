package cn.iocoder.yudao.module.transport.job;

import cn.iocoder.yudao.framework.quartz.core.handler.JobHandler;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverService;
import cn.iocoder.yudao.module.transport.service.transport.vehicle.VehicleService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 证照/保险到期预警 Job：扫描 T-30 / T-7 内到期（含已过期）的司机驾驶证与车辆保险，输出预警日志。
 * 需在管理端「定时任务」页手工注册本处理器后生效。
 */
@Component
@Slf4j
public class ExpiryWarningJob implements JobHandler {

    /** 预警窗口：30 天（黄色预警） */
    private static final Integer WARN_DAYS = 30;
    /** 预警窗口：7 天（红色预警） */
    private static final Integer CRITICAL_DAYS = 7;

    @Resource
    private DriverService driverService;
    @Resource
    private VehicleService vehicleService;

    @Override
    @TenantIgnore
    public String execute(String param) {
        List<DriverDO> drivers30 = driverService.getExpiringList(WARN_DAYS);
        List<DriverDO> drivers7 = driverService.getExpiringList(CRITICAL_DAYS);
        List<VehicleDO> vehicles30 = vehicleService.getExpiringList(WARN_DAYS);
        List<VehicleDO> vehicles7 = vehicleService.getExpiringList(CRITICAL_DAYS);

        for (DriverDO driver : drivers30) {
            log.warn("[execute][司机驾驶证到期预警：{}（{}）驾驶证 {} 于 {} 到期]",
                    driver.getName(), driver.getId(), driver.getLicenseNo(), driver.getLicenseExpireDate());
        }
        for (VehicleDO vehicle : vehicles30) {
            log.warn("[execute][车辆保险到期预警：{}（{}）保险于 {} 到期]",
                    vehicle.getPlateNo(), vehicle.getId(), vehicle.getInsuranceExpireDate());
        }
        String summary = String.format("证照/保险到期预警：司机 30 天内 %s 人（7 天内 %s 人），车辆 30 天内 %s 辆（7 天内 %s 辆）",
                drivers30.size(), drivers7.size(), vehicles30.size(), vehicles7.size());
        log.info("[execute][{}]", summary);
        return summary;
    }

}
