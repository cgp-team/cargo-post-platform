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
    /**
     * 是否有权查看订单完整明细（取件码、收件人 PII、货物明细）：
     * 下单人本人，或邮快件收件人（收件手机号与登录会员手机号一致）
     */
    boolean canViewOrderDetail(TransportOrderDO order, Long loginUserId);
    /** 货运子表（寄货货物信息） */
    CargoOrderDO getCargoOrder(Long orderId);
    /** 货运物品审核：通过 / 拒绝（危险品/违禁品） */
    void audit(@Valid OrderAuditReqVO reqVO);
    /**
     * 客户确认已按替代交接（CUSTOMER_TO_STATION）送到指定站点：
     * WAITING_CUSTOMER_ACTION → READY_FOR_POOL（可入池）。
     * 仅下单人本人可操作，且订单必须在「待客户操作」状态。
     */
    void confirmStationAction(Long userId, Long orderId);
}
