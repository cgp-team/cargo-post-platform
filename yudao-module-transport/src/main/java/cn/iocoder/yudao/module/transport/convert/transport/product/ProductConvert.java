package cn.iocoder.yudao.module.transport.convert.transport.product;

import cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface ProductConvert {
    ProductConvert INSTANCE = Mappers.getMapper(ProductConvert.class);
    ProductDO convert(ProductCreateReqVO bean);
    ProductDO convert(ProductUpdateReqVO bean);
}
