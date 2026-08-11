package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.controller.app.transport.send.vo.AppSendOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.CargoOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import jakarta.validation.Valid;

public interface TransportOrderService {
    Long create(@Valid TransportOrderCreateReqVO reqVO);
    void update(@Valid TransportOrderUpdateReqVO reqVO);
    void delete(Long id);
    TransportOrderDO get(Long id);
    PageResult<TransportOrderDO> getPage(TransportOrderPageReqVO reqVO);

    /** 小程序寄货创建货运订单（进调度池） */
    Long createSendOrder(Long userId, @Valid AppSendOrderCreateReqVO reqVO);
    /** 我的寄货记录分页 */
    PageResult<TransportOrderDO> getMySendPage(Long userId, PageParam pageParam);
    /** 按业务订单号查询（包裹追踪） */
    TransportOrderDO getByOrderNo(String orderNo);
    /** 货运子表（寄货货物信息） */
    CargoOrderDO getCargoOrder(Long orderId);
}
