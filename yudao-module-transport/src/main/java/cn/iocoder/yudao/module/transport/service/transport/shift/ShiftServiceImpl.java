package cn.iocoder.yudao.module.transport.service.transport.shift;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.shift.ShiftConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import cn.iocoder.yudao.module.transport.dal.mysql.shift.ShiftMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.SHIFT_NOT_EXISTS;

@Service @Validated
public class ShiftServiceImpl implements ShiftService {
    @Resource private ShiftMapper mapper;
    @Override public Long create(ShiftCreateReqVO reqVO) { ShiftDO o = ShiftConvert.INSTANCE.convert(reqVO); mapper.insert(o); return o.getId(); }
    @Override public void update(ShiftUpdateReqVO reqVO) { validateExists(reqVO.getId()); mapper.updateById(ShiftConvert.INSTANCE.convert(reqVO)); }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public ShiftDO get(Long id) { return validateExists(id); }
    @Override public PageResult<ShiftDO> getPage(ShiftPageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<ShiftDO> getSimpleList() { return mapper.selectList(); }
    private ShiftDO validateExists(Long id) { ShiftDO o = mapper.selectById(id); if (o == null) throw exception(SHIFT_NOT_EXISTS); return o; }
}
