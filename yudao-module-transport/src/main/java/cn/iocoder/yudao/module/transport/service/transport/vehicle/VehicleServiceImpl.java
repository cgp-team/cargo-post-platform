package cn.iocoder.yudao.module.transport.service.transport.vehicle;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.vehicle.VehicleConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.VEHICLE_NOT_EXISTS;

@Service @Validated
public class VehicleServiceImpl implements VehicleService {
    @Resource private VehicleMapper mapper;
    @Override public Long create(VehicleCreateReqVO reqVO) { VehicleDO o = VehicleConvert.INSTANCE.convert(reqVO); mapper.insert(o); return o.getId(); }
    @Override public void update(VehicleUpdateReqVO reqVO) { validateExists(reqVO.getId()); mapper.updateById(VehicleConvert.INSTANCE.convert(reqVO)); }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public VehicleDO get(Long id) { return validateExists(id); }
    @Override public PageResult<VehicleDO> getPage(VehiclePageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<VehicleDO> getSimpleList() { return mapper.selectList(); }
    @Override public List<VehicleDO> getExpiringList(Integer days) { return mapper.selectExpiringList(LocalDate.now().plusDays(days)); }
    private VehicleDO validateExists(Long id) { VehicleDO o = mapper.selectById(id); if (o == null) throw exception(VEHICLE_NOT_EXISTS); return o; }
}
