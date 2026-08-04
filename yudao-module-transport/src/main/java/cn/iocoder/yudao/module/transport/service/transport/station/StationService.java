package cn.iocoder.yudao.module.transport.service.transport.station;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import jakarta.validation.Valid;

public interface StationService {
    Long create(@Valid StationCreateReqVO reqVO);
    void update(@Valid StationUpdateReqVO reqVO);
    void delete(Long id);
    StationDO get(Long id);
    PageResult<StationDO> getPage(StationPageReqVO reqVO);
}
