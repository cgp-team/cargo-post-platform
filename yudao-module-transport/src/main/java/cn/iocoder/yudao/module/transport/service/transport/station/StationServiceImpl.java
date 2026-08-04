package cn.iocoder.yudao.module.transport.service.transport.station;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.station.StationConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.STATION_NOT_EXISTS;

@Service @Validated
public class StationServiceImpl implements StationService {
    @Resource private StationMapper mapper;
    @Override public Long create(StationCreateReqVO reqVO) { StationDO o = StationConvert.INSTANCE.convert(reqVO); mapper.insert(o); return o.getId(); }
    @Override public void update(StationUpdateReqVO reqVO) { validateExists(reqVO.getId()); mapper.updateById(StationConvert.INSTANCE.convert(reqVO)); }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public StationDO get(Long id) { return validateExists(id); }
    @Override public PageResult<StationDO> getPage(StationPageReqVO reqVO) { return mapper.selectPage(reqVO); }
    private StationDO validateExists(Long id) { StationDO o = mapper.selectById(id); if (o == null) throw exception(STATION_NOT_EXISTS); return o; }
}
