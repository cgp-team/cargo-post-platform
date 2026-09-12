package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TransportLegMapper extends BaseMapperX<TransportLegDO> {

    /** 按订单查运输段（按段序升序） */
    default List<TransportLegDO> selectListByOrderId(Long orderId) {
        return selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .eq(TransportLegDO::getOrderId, orderId)
                .orderByAsc(TransportLegDO::getLegSequence));
    }

    /** 按司机查运输段（按段序升序） */
    default List<TransportLegDO> selectListByDriverId(Long driverId) {
        return selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .eq(TransportLegDO::getDriverId, driverId)
                .orderByAsc(TransportLegDO::getOrderId)
                .orderByAsc(TransportLegDO::getLegSequence));
    }

    /** 按车辆查运输段（按段序升序） */
    default List<TransportLegDO> selectListByVehicleId(Long vehicleId) {
        return selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .eq(TransportLegDO::getVehicleId, vehicleId)
                .orderByAsc(TransportLegDO::getOrderId)
                .orderByAsc(TransportLegDO::getLegSequence));
    }

    /** 按运输方案查运输段（多段联运方案聚合，按订单、段序升序） */
    default List<TransportLegDO> selectListByPlanId(Long planId) {
        return selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .eq(TransportLegDO::getPlanId, planId)
                .orderByAsc(TransportLegDO::getOrderId)
                .orderByAsc(TransportLegDO::getLegSequence));
    }

    /**
     * 按「方案 + 订单」查运输段（段序升序）。
     *
     * 用于多段联运的幂等判定：幂等维度必须是<b>方案</b>而不是订单。
     * 只按 order_id 查会让新方案复用旧方案的段（P0-A：新方案在 transport_leg 里 0 行，
     * 聚合字段却来自旧段，司机端拿到的任务与当前方案错位）。
     */
    default List<TransportLegDO> selectListByPlanIdAndOrderId(Long planId, Long orderId) {
        return selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .eq(TransportLegDO::getPlanId, planId)
                .eq(TransportLegDO::getOrderId, orderId)
                .orderByAsc(TransportLegDO::getLegSequence));
    }

    /** 查某司机当前"进行中"的运输段（已分配~运输中，用于司机端当前任务） */
    default List<TransportLegDO> selectActiveByDriverId(Long driverId) {
        return selectList(new LambdaQueryWrapperX<TransportLegDO>()
                .eq(TransportLegDO::getDriverId, driverId)
                .in(TransportLegDO::getStatus, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
                .orderByAsc(TransportLegDO::getOrderId)
                .orderByAsc(TransportLegDO::getLegSequence));
    }

    /** 该订单当前最大段序（无段返回 0） */
    default Integer selectMaxSequenceByOrderId(Long orderId) {
        return selectListByOrderId(orderId).stream()
                .map(TransportLegDO::getLegSequence)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
    }

}
