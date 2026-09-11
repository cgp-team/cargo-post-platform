package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import cn.iocoder.yudao.module.transport.enums.dispatch.TransportLegStatusEnum;

import java.util.List;

/**
 * 多段联运服务：把订单规划为一段或多段运输（直达合理 → 1 段；否则经换乘站拆分为 2 段及以上）。
 */
public interface MultiLegService {

    /**
     * 为订单规划运输段（幂等：已有段直接返回）。
     * 直达合理 → 1 段（取货站 → 送达站）；否则 → 经换乘站拆分（取货站 → 换乘站 → 送达站）。
     *
     * @return 规划的运输段（按段序升序）
     */
    List<TransportLegDO> planLegs(Long orderId);

    /**
     * 为订单规划运输段并归属到指定调度方案（幂等）。
     *
     * @param planId 所属运输方案编号（可空）
     */
    List<TransportLegDO> planLegs(Long orderId, Long planId);

    /**
     * 规划运输段并**沿用调度算法的车辆/司机分配**（实现"一车多单"拼载）。
     *
     * <p>说明：本方法用 REQUIRES_NEW 独立事务写段，读不到调用方事务里尚未提交的
     * `transport_dispatch_plan_item`，所以由调用方把该订单在算法方案里的车辆/司机直接传入。</p>
     *
     * @param planVehicleId 算法分配给该订单的车辆编号（可空）
     * @param planDriverId  算法分配给该订单的司机编号（可空）
     */
    List<TransportLegDO> planLegs(Long orderId, Long planId, Long planVehicleId, Long planDriverId);

    /** 只规划不落库（后台"调度结果解释"：候选方案/评分/理由） */
    MultiLegPlanner.PlanResult preview(Long orderId);

    /** 订单的运输段（按段序升序） */
    List<TransportLegDO> getLegsByOrderId(Long orderId);

    /** 方案下的运输段（按订单、段序升序） */
    List<TransportLegDO> getLegsByPlanId(Long planId);

    /** 按编号查运输段（不存在抛 LEG_NOT_EXISTS） */
    TransportLegDO getLeg(Long legId);

    /**
     * 推进运输段状态（司机/用户动作）：走状态机守卫，非法流转抛 LEG_TRANSITION_ILLEGAL。
     * 含实际时间回写、车辆/司机状态同步、订单事件与通知。
     */
    void advanceLegStatus(Long legId, TransportLegStatusEnum targetStatus);

    /** 系统驱动的段状态变更（交接完成/超时/重调度），跳过状态机守卫 */
    void forceLegStatus(Long legId, TransportLegStatusEnum targetStatus, String reason);

    /**
     * 异常重调度：只重新规划**受影响的这一段**（需求 §77/§108），
     * 已完成的段不动、订单不重建；找不到无冲突的替代车辆/司机时保持异常并提示人工。
     *
     * @return 重调度后的运输段
     */
    TransportLegDO replanLeg(Long legId, String reason);

    /** 该订单是否需要多段联运（由规划器结果决定：无直达线路或联运更优） */
    boolean needMultiLeg(Long orderId);

}
