package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import jakarta.validation.Valid;

public interface DriverService {
    Long create(@Valid DriverCreateReqVO reqVO);
    void update(@Valid DriverUpdateReqVO reqVO);
    void delete(Long id);
    DriverDO get(Long id);
    PageResult<DriverDO> getPage(DriverPageReqVO reqVO);
}
