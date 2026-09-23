package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;

import java.util.List;

/**
 * 调度闭环 Service 接口：订单归集入池、手工/智能派单、方案审核下发、发车核验。
 */
public interface DispatchService {

    /**
     * 获得调度订单池分页（待入池 + 已入池订单）
     */
    PageResult<TransportOrderDO> getOrderPoolPage(DispatchPoolPageReqVO reqVO);

    /**
     * 订单归集：把创建时间落在批次区间内的待入池订单批量入池
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
     * 智能派单前约束校验：订单池按类别统计 + 车辆容量对比 + 站点作业标记 + 客运时序检查，
     * 输出运力不足预警（对应故事「约束校验/运力预警」环节，规划前主动校验）。
     */
    DispatchValidateRespVO validate(DispatchValidateReqVO reqVO);

    /**
     * 返程结算：按日期区间汇总执行中/已完成方案的里程、乘客数、包裹量、乘客平均等待与分车统计
     * （对应故事「返程结算/运营报表」环节；方案 COMPLETED 流转留待后续迭代）。
     */
    DispatchSettlementRespVO settlement(DispatchSettlementReqVO reqVO);

    /**
     * 获得调度方案（含经停明细）
     */
    DispatchPlanRespVO getPlan(Long id);

    /**
     * 获得调度方案的真实道路地图数据（按车辆 + 经停序号的每段轨迹；高德不可用时段落为两点直线并标注 provider）。
     */
    DispatchRoadmapRespVO getPlanRoadmap(Long id);

    /**
     * 两点之间的真实道路轨迹（按订单视角画线路用）：高德不可用时返回空列表（前端回退直连并标注估算）。
     */
    java.util.List<DispatchRoadmapRespVO.Point> routeBetween(Double fromLongitude, Double fromLatitude,
                                                             Double toLongitude, Double toLatitude);

    /**
     * Two points' real road geometry; when {@code legId} is given the leg's operating line corridor is
     * used first, so the order view draws the same road the bus actually drives.
     */
    java.util.List<DispatchRoadmapRespVO.Point> routeBetween(Double fromLongitude, Double fromLatitude,
                                                             Double toLongitude, Double toLatitude, Long legId);

    /**
     * 订单池手动取消：把"待入池/已入池"的订单置为已取消（其它订单不受影响）。
     *
     * <p>只允许取消尚未进入方案执行的订单（已分配/已发车/在途的订单不能在这里取消，
     * 避免把已经跑在路上的货取消掉）。</p>
     *
     * @return 实际取消的订单数
     */
    int cancelPoolOrders(java.util.List<Long> orderIds);

    /**
     * 预热真实道路轨迹（高德配额恢复后跑一次）：订单池订单的取送站点对 + 今天方案里运输段的起终点对，
     * 逐对调用高德并把取到的轨迹落库到对应运输段（transport_leg.navigation_polyline）。
     *
     * @return 本次成功取到并落库/预热的站点对数
     */
    int prefetchRoadGeometry();

    /**
     * 获得调度方案分页
     */
    PageResult<DispatchPlanDO> getPlanPage(DispatchPlanPageReqVO reqVO);


    /** 动态插单：委托算法 /api/v1/dispatch/allocate（DISPATCH_CORE_V047）。 */
    java.util.Map<String, Object> allocateDynamic(java.util.Map<String, Object> payload);
}
