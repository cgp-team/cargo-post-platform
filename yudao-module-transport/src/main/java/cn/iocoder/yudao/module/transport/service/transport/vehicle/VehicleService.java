package cn.iocoder.yudao.module.transport.service.transport.vehicle;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import jakarta.validation.Valid;

import java.util.List;

public interface VehicleService {
    Long create(@Valid VehicleCreateReqVO reqVO);
    void update(@Valid VehicleUpdateReqVO reqVO);
    void delete(Long id);
    VehicleDO get(Long id);
    PageResult<VehicleDO> getPage(VehiclePageReqVO reqVO);
    List<VehicleDO> getSimpleList();

    /** 查询 days 天内（含已过期）保险到期的车辆，按到期日升序 */
    List<VehicleDO> getExpiringList(Integer days);
}
