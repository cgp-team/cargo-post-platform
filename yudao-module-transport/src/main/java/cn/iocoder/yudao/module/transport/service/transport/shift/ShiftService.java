package cn.iocoder.yudao.module.transport.service.transport.shift;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.shift.ShiftDO;
import jakarta.validation.Valid;

import java.util.List;

public interface ShiftService {
    Long create(@Valid ShiftCreateReqVO reqVO);
    void update(@Valid ShiftUpdateReqVO reqVO);
    void delete(Long id);
    ShiftDO get(Long id);
    PageResult<ShiftDO> getPage(ShiftPageReqVO reqVO);
    List<ShiftDO> getSimpleList();
}
