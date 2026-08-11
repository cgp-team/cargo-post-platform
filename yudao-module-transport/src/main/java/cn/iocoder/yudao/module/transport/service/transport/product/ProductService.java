package cn.iocoder.yudao.module.transport.service.transport.product;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import jakarta.validation.Valid;

import java.util.List;

public interface ProductService {
    Long create(@Valid ProductCreateReqVO reqVO);
    void update(@Valid ProductUpdateReqVO reqVO);
    void delete(Long id);
    ProductDO get(Long id);
    PageResult<ProductDO> getPage(ProductPageReqVO reqVO);
    List<ProductDO> getSimpleList();
    /** 全部上架商品（小程序商城列表） */
    List<ProductDO> getOnShelfList();
}
