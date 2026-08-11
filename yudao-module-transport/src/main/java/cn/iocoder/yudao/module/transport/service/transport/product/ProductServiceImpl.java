package cn.iocoder.yudao.module.transport.service.transport.product;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo.*;
import cn.iocoder.yudao.module.transport.convert.transport.product.ProductConvert;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import cn.iocoder.yudao.module.transport.dal.mysql.product.ProductMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.PRODUCT_NAME_DUPLICATE;
import static cn.iocoder.yudao.module.transport.enums.ErrorCodeConstants.PRODUCT_NOT_EXISTS;

@Service @Validated
public class ProductServiceImpl implements ProductService {
    @Resource private ProductMapper mapper;

    @Override public Long create(ProductCreateReqVO reqVO) {
        validateNameUnique(reqVO.getName(), null);
        ProductDO o = ProductConvert.INSTANCE.convert(reqVO);
        mapper.insert(o);
        return o.getId();
    }
    @Override public void update(ProductUpdateReqVO reqVO) {
        validateExists(reqVO.getId());
        validateNameUnique(reqVO.getName(), reqVO.getId());
        mapper.updateById(ProductConvert.INSTANCE.convert(reqVO));
    }
    @Override public void delete(Long id) { validateExists(id); mapper.deleteById(id); }
    @Override public ProductDO get(Long id) { return validateExists(id); }
    @Override public PageResult<ProductDO> getPage(ProductPageReqVO reqVO) { return mapper.selectPage(reqVO); }
    @Override public List<ProductDO> getSimpleList() { return mapper.selectList(); }
    @Override public List<ProductDO> getOnShelfList() { return mapper.selectOnShelfList(); }

    private ProductDO validateExists(Long id) {
        ProductDO o = mapper.selectById(id);
        if (o == null) throw exception(PRODUCT_NOT_EXISTS);
        return o;
    }
    /** 校验商品名称唯一（新增 name、更新时排除自身） */
    private void validateNameUnique(String name, Long id) {
        ProductDO o = mapper.selectByName(name);
        if (o != null && !o.getId().equals(id)) {
            throw exception(PRODUCT_NAME_DUPLICATE);
        }
    }
}
