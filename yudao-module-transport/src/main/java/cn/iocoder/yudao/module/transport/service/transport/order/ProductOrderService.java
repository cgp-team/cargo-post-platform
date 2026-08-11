package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 农产品商城订单 Service。
 */
public interface ProductOrderService {

    /** 小程序下单（货到付款） */
    Long createOrder(Long userId, @Valid AppProductOrderCreateReqVO reqVO);

    /** 我的订单分页 */
    PageResult<ProductOrderDO> getMyPage(Long userId, ProductOrderPageReqVO reqVO);

    /** 取消订单（仅待发货，恢复库存） */
    void cancel(Long userId, Long id);

    /** 管理端：分页 */
    PageResult<ProductOrderDO> getPage(ProductOrderPageReqVO reqVO);

    /** 管理端：详情 */
    ProductOrderDO get(Long id);

    /** 管理端：发货 */
    void ship(Long id);

    /** 管理端：完成 */
    void complete(Long id);

    /** 订单明细 */
    List<ProductOrderItemDO> getItemsByOrderId(Long orderId);
}
