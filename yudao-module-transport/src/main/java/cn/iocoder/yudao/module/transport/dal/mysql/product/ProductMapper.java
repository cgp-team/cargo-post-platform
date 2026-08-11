package cn.iocoder.yudao.module.transport.dal.mysql.product;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo.ProductPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapperX<ProductDO> {

    default PageResult<ProductDO> selectPage(ProductPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<ProductDO>()
                .likeIfPresent(ProductDO::getName, reqVO.getName())
                .likeIfPresent(ProductDO::getFromVillage, reqVO.getFromVillage())
                .eqIfPresent(ProductDO::getStatus, reqVO.getStatus())
                .betweenIfPresent(ProductDO::getCreateTime, reqVO.getCreateTime())
                .orderByAsc(ProductDO::getSort).orderByDesc(ProductDO::getId));
    }

    default ProductDO selectByName(String name) {
        return selectOne(ProductDO::getName, name);
    }

    /** 查询全部上架商品（按 sort 升序、id 倒序） */
    default List<ProductDO> selectOnShelfList() {
        return selectList(new LambdaQueryWrapperX<ProductDO>()
                .eq(ProductDO::getStatus, 0)
                .orderByAsc(ProductDO::getSort).orderByDesc(ProductDO::getId));
    }

    /** 扣减库存（stock >= quantity 条件保证不超卖，返回 0 表示库存不足） */
    @Update("UPDATE transport_product SET stock = stock - #{quantity}, update_time = NOW() " +
            "WHERE id = #{id} AND stock >= #{quantity} AND deleted = 0")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /** 恢复库存（取消订单回补） */
    @Update("UPDATE transport_product SET stock = stock + #{quantity}, update_time = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}
