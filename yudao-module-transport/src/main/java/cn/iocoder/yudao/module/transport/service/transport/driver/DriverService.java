package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import jakarta.validation.Valid;

import java.util.List;

public interface DriverService {
    Long create(@Valid DriverCreateReqVO reqVO);
    void update(@Valid DriverUpdateReqVO reqVO);
    void delete(Long id);
    DriverDO get(Long id);
    PageResult<DriverDO> getPage(DriverPageReqVO reqVO);
    List<DriverDO> getSimpleList();

    /** 查询 days 天内（含已过期）驾驶证到期的司机，按到期日升序 */
    List<DriverDO> getExpiringList(Integer days);
}
