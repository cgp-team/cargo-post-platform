package cn.iocoder.yudao.module.transport.dal.mysql.dispatch;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo.HandoverPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface TransportHandoverMapper extends BaseMapperX<TransportHandoverDO> {

    default PageResult<TransportHandoverDO> selectPage(HandoverPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<TransportHandoverDO>()
                .eqIfPresent(TransportHandoverDO::getOrderId, reqVO.getOrderId())
                .eqIfPresent(TransportHandoverDO::getStationId, reqVO.getStationId())
                .eqIfPresent(TransportHandoverDO::getFromDriverId, reqVO.getFromDriverId())
                .eqIfPresent(TransportHandoverDO::getToDriverId, reqVO.getToDriverId())
                .eqIfPresent(TransportHandoverDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(TransportHandoverDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(TransportHandoverDO::getId));
    }

    /** 按订单查交接记录（按创建时间升序） */
    default List<TransportHandoverDO> selectListByOrderId(Long orderId) {
        return selectList(new LambdaQueryWrapperX<TransportHandoverDO>()
                .eq(TransportHandoverDO::getOrderId, orderId)
                .orderByAsc(TransportHandoverDO::getId));
    }

    /** 与某司机相关（交出或接收）的交接记录 */
    default List<TransportHandoverDO> selectListByDriverId(Long driverId) {
        return selectList(new LambdaQueryWrapperX<TransportHandoverDO>()
                .and(w -> w.eq(TransportHandoverDO::getFromDriverId, driverId)
                        .or().eq(TransportHandoverDO::getToDriverId, driverId))
                .orderByDesc(TransportHandoverDO::getId));
    }

    /** 按来源运输段查交接（一个来源段只对应一个换乘交接） */
    default TransportHandoverDO selectByLegFrom(Long legFromId) {
        return selectOne(new LambdaQueryWrapperX<TransportHandoverDO>()
                .eq(TransportHandoverDO::getLegFromId, legFromId)
                .last("LIMIT 1"));
    }

    /** 按目的运输段查交接 */
    default TransportHandoverDO selectByLegTo(Long legToId) {
        return selectOne(new LambdaQueryWrapperX<TransportHandoverDO>()
                .eq(TransportHandoverDO::getLegToId, legToId)
                .last("LIMIT 1"));
    }

    /** 按运输方案查交接 */
    default List<TransportHandoverDO> selectListByPlanId(Long planId) {
        return selectList(new LambdaQueryWrapperX<TransportHandoverDO>()
                .eq(TransportHandoverDO::getPlanId, planId)
                .orderByAsc(TransportHandoverDO::getId));
    }

}
