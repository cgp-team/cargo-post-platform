package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;

/**
 * 调度闭环 Service 接口：订单归集入池、手工/智能派单、方案审核下发、发车核验。
 */
public interface DispatchService {

    /**
     * 获得调度订单池分页（待调度 + 已入池订单）
     */
    PageResult<TransportOrderDO> getOrderPoolPage(DispatchPoolPageReqVO reqVO);

    /**
     * 订单归集：把创建时间落在批次区间内的待调度订单批量入池
     *
     * @return 入池订单数量，0 条时不报错返回 0
     */
    int collectOrders(DispatchCollectReqVO reqVO);

    /**
     * 手工派单：按给定订单顺序生成闭环经停，校验容量/时序后生成待审核方案
     *
     * @return 方案编号
     */
    Long createManualPlan(DispatchManualPlanReqVO reqVO);

    /**
     * 智能派单：构建快照调用算法，可行则生成待审核方案
     *
     * @return 方案编号
     */
    Long createSmartPlan(DispatchSmartPlanReqVO reqVO);

    /**
     * 方案审核：通过则下发，驳回则作废且订单回到订单池
     */
    void reviewPlan(DispatchPlanReviewReqVO reqVO);

    /**
     * 发车核验：通过则方案进入执行中，对应车辆订单置为已发车
     */
    void departureCheck(DispatchCheckReqVO reqVO);

    /**
     * 获得调度方案（含经停明细）
     */
    DispatchPlanRespVO getPlan(Long id);

    /**
     * 获得调度方案分页
     */
    PageResult<DispatchPlanDO> getPlanPage(DispatchPlanPageReqVO reqVO);

}
