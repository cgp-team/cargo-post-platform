package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import jakarta.validation.Valid;

public interface TransportOrderService {
    Long create(@Valid TransportOrderCreateReqVO reqVO);
    void update(@Valid TransportOrderUpdateReqVO reqVO);
    void delete(Long id);
    TransportOrderDO get(Long id);
    PageResult<TransportOrderDO> getPage(TransportOrderPageReqVO reqVO);
}
