package cn.iocoder.yudao.module.transport.dal.mysql.vehicle;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VehicleLocationMapper extends BaseMapperX<VehicleLocationDO> {

    /** 按车辆查询最新位置（每车一行） */
    default VehicleLocationDO selectByVehicleId(Long vehicleId) {
        return selectOne(VehicleLocationDO::getVehicleId, vehicleId);
    }

    /** 查询上报时间不早于指定时间的位置记录（实时性过滤） */
    default List<VehicleLocationDO> selectRecent(LocalDateTime since) {
        return selectList(new LambdaQueryWrapperX<VehicleLocationDO>()
                .ge(VehicleLocationDO::getReportTime, since));
    }
}
