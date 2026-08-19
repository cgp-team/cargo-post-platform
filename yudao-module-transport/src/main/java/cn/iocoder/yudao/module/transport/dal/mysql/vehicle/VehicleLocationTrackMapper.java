package cn.iocoder.yudao.module.transport.dal.mysql.vehicle;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleLocationTrackDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VehicleLocationTrackMapper extends BaseMapperX<VehicleLocationTrackDO> {

    /** 查询车辆不早于指定时间的轨迹（时间升序，最多 limit 条，防大包） */
    default List<VehicleLocationTrackDO> selectByVehicleIdSince(Long vehicleId, LocalDateTime since, int limit) {
        return selectList(new LambdaQueryWrapperX<VehicleLocationTrackDO>()
                .eq(VehicleLocationTrackDO::getVehicleId, vehicleId)
                .ge(VehicleLocationTrackDO::getReportTime, since)
                .orderByAsc(VehicleLocationTrackDO::getReportTime)
                .last("LIMIT " + limit));
    }

    /** 查询车辆在 [start, end) 时间范围内的轨迹（时间升序，最多 limit 条，防大包） */
    default List<VehicleLocationTrackDO> selectByVehicleIdAndTimeRange(Long vehicleId,
                                                                       LocalDateTime start, LocalDateTime end, int limit) {
        return selectList(new LambdaQueryWrapperX<VehicleLocationTrackDO>()
                .eq(VehicleLocationTrackDO::getVehicleId, vehicleId)
                .ge(VehicleLocationTrackDO::getReportTime, start)
                .lt(VehicleLocationTrackDO::getReportTime, end)
                .orderByAsc(VehicleLocationTrackDO::getReportTime)
                .last("LIMIT " + limit));
    }
}
