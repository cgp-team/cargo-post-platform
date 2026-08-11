package cn.iocoder.yudao.module.transport.dal.mysql.order;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductOrderMapper extends BaseMapperX<ProductOrderDO> {

    default PageResult<ProductOrderDO> selectPage(ProductOrderPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProductOrderDO>()
                .likeIfPresent(ProductOrderDO::getOrderNo, reqVO.getOrderNo())
                .eqIfPresent(ProductOrderDO::getUserId, reqVO.getUserId())
                .eqIfPresent(ProductOrderDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(ProductOrderDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(ProductOrderDO::getId));
    }

    /** 当前用户订单分页（小程序「我的订单」） */
    default PageResult<ProductOrderDO> selectPageByUser(ProductOrderPageReqVO reqVO, Long userId) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProductOrderDO>()
                .eq(ProductOrderDO::getUserId, userId)
                .eqIfPresent(ProductOrderDO::getStatus, reqVO.getStatus())
                .orderByDesc(ProductOrderDO::getId));
    }

    default ProductOrderDO selectByOrderNo(String orderNo) {
        return selectOne(ProductOrderDO::getOrderNo, orderNo);
    }
}
