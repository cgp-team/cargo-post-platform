package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.driver.DriverConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.DRIVER_NOT_EXISTS;

@Service @Validated
public class DriverServiceImpl implements DriverService {
    @Resource private DriverMapper mapper;
    @Override public Long create(DriverCreateReqVO reqVO) { DriverDO o = DriverConvert.INSTANCE.convert(reqVO); mapper.insert(o); return o.getId(); }
    @Override public void update(DriverUpdateReqVO reqVO) { validateExists(reqVO.getId()); mapper.updateById(DriverConvert.INSTANCE.convert(reqVO)); }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public DriverDO get(Long id) { return validateExists(id); }
    @Override public PageResult<DriverDO> getPage(DriverPageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<DriverDO> getSimpleList() { return mapper.selectList(); }
    @Override public List<DriverDO> getExpiringList(Integer days) { return mapper.selectExpiringList(LocalDate.now().plusDays(days)); }
    private DriverDO validateExists(Long id) { DriverDO o = mapper.selectById(id); if (o == null) throw exception(DRIVER_NOT_EXISTS); return o; }
}
