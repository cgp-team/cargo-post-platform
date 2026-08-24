package cn.iocoder.yudao.module.transport.controller.app.transport.product;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.app.transport.product.vo.AppProductRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.product.ProductDO;
import cn.iocoder.yudao.module.transport.service.transport.product.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 商品")
@RestController
@RequestMapping("/transport/product")
@Validated
public class AppProductController {
    @Resource private ProductService productService;

    @GetMapping("/list")
    @Operation(summary = "获得上架商品列表")
    @PermitAll
    public CommonResult<List<AppProductRespVO>> list() {
        return success(BeanUtils.toBean(productService.getOnShelfList(), AppProductRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得上架商品分页")
    @PermitAll
    public CommonResult<PageResult<AppProductRespVO>> page(PageParam pageParam) {
        PageResult<ProductDO> pageResult = productService.getOnShelfPage(pageParam);
        return success(BeanUtils.toBean(pageResult, AppProductRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获得商品详情")
    @Parameter(name = "id", description = "商品编号", required = true)
    @PermitAll
    public CommonResult<AppProductRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(productService.get(id), AppProductRespVO.class));
    }
}
