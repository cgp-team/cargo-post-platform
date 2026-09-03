package cn.iocoder.yudao.module.transport.dal.mysql.simulation;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.simulation.SimulationEventDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SimulationEventMapper extends BaseMapperX<SimulationEventDO> {
}
