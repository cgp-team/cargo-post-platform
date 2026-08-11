package cn.iocoder.yudao.module.transport.dal.mysql.order;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.TransportOrderPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TransportOrderMapper extends BaseMapperX<TransportOrderDO> {
    default PageResult<TransportOrderDO> selectPage(TransportOrderPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<TransportOrderDO>()
                .likeIfPresent(TransportOrderDO::getOrderNo, reqVO.getOrderNo())
                .eqIfPresent(TransportOrderDO::getOrderType, reqVO.getOrderType())
                .eqIfPresent(TransportOrderDO::getStatus, reqVO.getStatus())
                .eqIfPresent(TransportOrderDO::getPickupStationId, reqVO.getPickupStationId())
                .eqIfPresent(TransportOrderDO::getDeliveryStationId, reqVO.getDeliveryStationId())
                .betweenIfPresent(TransportOrderDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(TransportOrderDO::getId));
    }

    /** 小程序「我的寄货」分页（按会员过滤） */
    default PageResult<TransportOrderDO> selectPageByMemberUser(PageParam pageParam, Long userId) {
        return selectPage(pageParam, new LambdaQueryWrapperX<TransportOrderDO>()
                .eq(TransportOrderDO::getMemberUserId, userId)
                .orderByDesc(TransportOrderDO::getId));
    }

    default TransportOrderDO selectByOrderNo(String orderNo) {
        return selectOne(TransportOrderDO::getOrderNo, orderNo);
    }
}
