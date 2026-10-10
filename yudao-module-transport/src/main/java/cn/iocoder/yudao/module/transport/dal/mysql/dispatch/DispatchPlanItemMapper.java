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

    /**
     * 在途方案（状态 ∈ planStatuses）占用的车辆编号：一条 SQL（DISTINCT vehicle_id + 方案状态子查询），
     * 替代「先查方案再查明细」两次往返与明细全量载入。
     */
    default List<Long> selectBusyVehicleIds(java.util.Collection<Integer> planStatuses) {
        if (planStatuses == null || planStatuses.isEmpty()) {
            return List.of();
        }
        String statusCsv = planStatuses.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        return selectObjs(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DispatchPlanItemDO>()
                        .select("DISTINCT vehicle_id")
                        .isNotNull("vehicle_id")
                        .inSql("plan_id", "SELECT id FROM transport_dispatch_plan WHERE deleted = 0 AND status IN ("
                                + statusCsv + ")"))
                .stream()
                .filter(java.util.Objects::nonNull)
                .map(o -> ((Number) o).longValue())
                .toList();
    }
}
