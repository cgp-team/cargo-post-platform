package cn.iocoder.yudao.module.transport.dal.mysql.shift;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftExecutionDO;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ShiftExecutionMapper extends BaseMapperX<ShiftExecutionDO> {

    /** 按班次 + 司机 + 执行日期查询当天执行记录（唯一键维度） */
    default ShiftExecutionDO selectByShiftAndDriverAndDate(Long shiftId, Long driverId, LocalDate execDate) {
        return selectOne(new LambdaQueryWrapperX<ShiftExecutionDO>()
                .eq(ShiftExecutionDO::getShiftId, shiftId)
                .eq(ShiftExecutionDO::getDriverId, driverId)
                .eq(ShiftExecutionDO::getExecDate, execDate));
    }

    /** 按班次集合 + 执行日期查询执行记录（司机维度不限） */
    default List<ShiftExecutionDO> selectListByShiftIdsAndExecDate(Collection<Long> shiftIds, LocalDate execDate) {
        return selectList(new LambdaQueryWrapperX<ShiftExecutionDO>()
                .inIfPresent(ShiftExecutionDO::getShiftId, shiftIds)
                .eq(ShiftExecutionDO::getExecDate, execDate));
    }

    /**
     * 按司机 + 执行日期查询当天执行记录（可能多条：一天内跑过多个班次）。
     * 智能派单的经停明细不绑定固定班次，装车/妥投要按"司机实际发车的那条执行记录"兜底。
     */
    default List<ShiftExecutionDO> selectListByDriverAndDate(Long driverId, LocalDate execDate) {
        return selectList(new LambdaQueryWrapperX<ShiftExecutionDO>()
                .eq(ShiftExecutionDO::getDriverId, driverId)
                .eq(ShiftExecutionDO::getExecDate, execDate)
                .orderByDesc(ShiftExecutionDO::getId));
    }

    /**
     * 按车辆集合查询班次执行记录（智能派单时给每辆车解析它自己的公交线路骨架用）。
     * 同一辆车可能有多条历史执行记录，这里按 id 倒序取最新（当前正执行的班次优先）。
     */
    default List<ShiftExecutionDO> selectListByVehicleIds(Collection<Long> vehicleIds) {
        return selectList(new LambdaQueryWrapperX<ShiftExecutionDO>()
                .inIfPresent(ShiftExecutionDO::getVehicleId, vehicleIds)
                .orderByDesc(ShiftExecutionDO::getId));
    }

    /**
     * 已装件数原子 +1：防并发装车丢更新。
     */
    default int incrementLoadedCount(Long id) {
        return update(new ShiftExecutionDO(),
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ShiftExecutionDO>()
                        .eq(ShiftExecutionDO::getId, id)
                        .setSql("loaded_count = IFNULL(loaded_count, 0) + 1"));
    }

    /**
     * 容量受限的原子装车 +1（BE-06）：把容量条件下推到同一条 UPDATE，
     * 消除"读-比-自增"TOCTOU——并发下 loaded_count 不会超过 capacity。
     * 返回 0 表示已满（容量条件未命中），调用方应抛 DRIVER_CARGO_FULL。
     */
    default int incrementLoadedCountWithinCapacity(Long id, int capacity) {
        return update(new ShiftExecutionDO(),
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ShiftExecutionDO>()
                        .eq(ShiftExecutionDO::getId, id)
                        .apply("IFNULL(loaded_count, 0) < {0}", capacity)
                        .setSql("loaded_count = IFNULL(loaded_count, 0) + 1"));
    }

    /**
     * 已装件数原子 -1（地板 0）：防并发妥投/核销丢更新。
     */
    default int decrementLoadedCount(Long id) {
        return update(new ShiftExecutionDO(),
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ShiftExecutionDO>()
                        .eq(ShiftExecutionDO::getId, id)
                        .setSql("loaded_count = GREATEST(IFNULL(loaded_count, 0) - 1, 0)"));
    }
}
