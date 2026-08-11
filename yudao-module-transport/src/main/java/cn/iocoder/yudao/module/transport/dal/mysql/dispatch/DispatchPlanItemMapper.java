package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanItemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DispatchPlanItemMapper extends BaseMapperX<DispatchPlanItemDO> {

    /** 按司机查询调度方案明细（算法派单结果按司机读取，预留） */
    default List<DispatchPlanItemDO> selectListByDriverId(Long driverId) {
        return selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getDriverId, driverId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
    }

    /** 按车辆查询调度方案明细 */
    default List<DispatchPlanItemDO> selectListByVehicleId(Long vehicleId) {
        return selectList(new LambdaQueryWrapperX<DispatchPlanItemDO>()
                .eq(DispatchPlanItemDO::getVehicleId, vehicleId)
                .orderByAsc(DispatchPlanItemDO::getVisitSequence));
    }
}
