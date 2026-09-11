package cn.iocoder.yudao.module.transport.service.transport.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderCreateReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.AppProductOrderTraceRespVO;
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

    /** 管理端：发货（可关联承运车辆/班次，供小程序溯源） */
    /**
     * 发货（派单给司机）。
     *
     * @param deliverStationId 交付站点（快递集散中心/村级网点）；为空时默认取班次线路终点站
     */
    void ship(Long id, Long vehicleId, Long shiftId, Long deliverStationId);

    /** 兼容旧调用：交付站点默认班次线路终点站 */
    default void ship(Long id, Long vehicleId, Long shiftId) {
        ship(id, vehicleId, shiftId, null);
    }

    /** 管理端：完成 */
    void complete(Long id);

    /** 订单明细 */
    List<ProductOrderItemDO> getItemsByOrderId(Long orderId);

    /** 小程序：订单溯源（承运车辆/班次/线路站点/当天轨迹/最新位置）；未关联承运车辆时返回空语义 */
    AppProductOrderTraceRespVO getTrace(Long userId, Long id);

    // ==================== 司机端执行（商城订单同样走"装车 → 妥投"闭环） ====================

    /**
     * 司机端：本车待执行的商城订单（已发货、未妥投、承运车辆=该司机绑定车辆）。
     * 与货运订单同口径：司机只能看到派给自己的单。
     */
    List<ProductOrderDO> getDriverDeliveryTasks(Long vehicleId);

    /** 司机端：装车确认（拍照核验凭证），订单仍是已发货，配送中 */
    void driverLoad(Long driverId, Long vehicleId, Long orderId, String photoUrl);

    /** 司机端：妥投完成（交付凭证），订单转已完成 */
    void driverDeliver(Long driverId, Long vehicleId, Long orderId, String photoUrl);
}
