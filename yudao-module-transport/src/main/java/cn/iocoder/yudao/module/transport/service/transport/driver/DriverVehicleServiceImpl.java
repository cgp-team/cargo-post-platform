package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehicleBindReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehiclePageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverVehicleMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.*;

/**
 * 司机车辆绑定 Service 实现
 */
@Service
@Validated
@Slf4j
public class DriverVehicleServiceImpl implements DriverVehicleService {

    /** 绑定状态：绑定中 */
    private static final Integer STATUS_BOUND = 1;
    /** 绑定状态：已解绑 */
    private static final Integer STATUS_UNBOUND = 0;

    @Resource
    private DriverVehicleMapper driverVehicleMapper;
    @Resource
    private DriverMapper driverMapper;
    @Resource
    private VehicleMapper vehicleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bind(DriverVehicleBindReqVO reqVO) {
        // 校验司机、车辆存在
        DriverDO driver = driverMapper.selectById(reqVO.getDriverId());
        if (driver == null) {
            throw exception(DRIVER_NOT_EXISTS);
        }
        VehicleDO vehicle = vehicleMapper.selectById(reqVO.getVehicleId());
        if (vehicle == null) {
            throw exception(VEHICLE_NOT_EXISTS);
        }
        // 同一司机已有有效绑定则先自动解绑（一司机同时只绑定一辆车）
        unbindAll(driverVehicleMapper.selectActiveByDriverId(reqVO.getDriverId()));
        // 同一车辆已有有效绑定则一并解绑（一车同时只归属一名司机，即一司机一车、一车一司机）
        unbindAll(driverVehicleMapper.selectActiveByVehicleId(reqVO.getVehicleId()));
        // 插入新绑定
        DriverVehicleDO bind = new DriverVehicleDO();
        bind.setDriverId(reqVO.getDriverId());
        bind.setVehicleId(reqVO.getVehicleId());
        bind.setBindTime(LocalDateTime.now());
        bind.setStatus(STATUS_BOUND);
        driverVehicleMapper.insert(bind);
    }

    /** 批量解绑有效绑定记录 */
    private void unbindAll(List<DriverVehicleDO> active) {
        for (DriverVehicleDO old : active) {
            DriverVehicleDO unbind = new DriverVehicleDO();
            unbind.setId(old.getId());
            unbind.setStatus(STATUS_UNBOUND);
            unbind.setUnbindTime(LocalDateTime.now());
            driverVehicleMapper.updateById(unbind);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long id) {
        DriverVehicleDO exist = driverVehicleMapper.selectById(id);
        if (exist == null) {
            throw exception(DRIVER_VEHICLE_NOT_EXISTS);
        }
        if (Integer.valueOf(STATUS_UNBOUND).equals(exist.getStatus())) {
            return; // 已是解绑状态，幂等返回
        }
        DriverVehicleDO unbind = new DriverVehicleDO();
        unbind.setId(id);
        unbind.setStatus(STATUS_UNBOUND);
        unbind.setUnbindTime(LocalDateTime.now());
        driverVehicleMapper.updateById(unbind);
    }

    @Override
    public PageResult<DriverVehicleDO> getPage(DriverVehiclePageReqVO pageReqVO) {
        return driverVehicleMapper.selectPage(pageReqVO, new LambdaQueryWrapperX<DriverVehicleDO>()
                .eqIfPresent(DriverVehicleDO::getDriverId, pageReqVO.getDriverId())
                .eqIfPresent(DriverVehicleDO::getVehicleId, pageReqVO.getVehicleId())
                .eqIfPresent(DriverVehicleDO::getStatus, pageReqVO.getStatus())
                .betweenIfPresent(DriverVehicleDO::getBindTime, pageReqVO.getBindTimeStart(), pageReqVO.getBindTimeEnd())
                .orderByDesc(DriverVehicleDO::getId));
    }

    @Override
    public List<DriverVehicleDO> listByDriver(Long driverId) {
        return driverVehicleMapper.selectList(new LambdaQueryWrapperX<DriverVehicleDO>()
                .eq(DriverVehicleDO::getDriverId, driverId)
                .orderByDesc(DriverVehicleDO::getId));
    }

}
