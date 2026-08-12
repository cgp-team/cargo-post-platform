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
}
