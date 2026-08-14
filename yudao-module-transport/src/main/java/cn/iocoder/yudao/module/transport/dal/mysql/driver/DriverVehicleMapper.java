package cn.iocoder.yudao.module.transport.dal.mysql.driver;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DriverVehicleMapper extends BaseMapperX<DriverVehicleDO> {

    /** 查询当前绑定中的人车绑定关系（绑定中且未解绑） */
    default List<DriverVehicleDO> selectActiveBindings() {
        return selectList(new LambdaQueryWrapperX<DriverVehicleDO>()
                .eq(DriverVehicleDO::getStatus, 1)
                .isNull(DriverVehicleDO::getUnbindTime)
                .orderByAsc(DriverVehicleDO::getVehicleId));
    }

    /** 查询司机当前有效绑定（绑定中且未解绑） */
    default List<DriverVehicleDO> selectActiveByDriverId(Long driverId) {
        return selectList(new LambdaQueryWrapperX<DriverVehicleDO>()
                .eq(DriverVehicleDO::getDriverId, driverId)
                .eq(DriverVehicleDO::getStatus, 1)
                .isNull(DriverVehicleDO::getUnbindTime));
    }

}
