package cn.iocoder.yudao.module.transport.controller.admin.transport.product;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.product.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.product.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 商品管理")
@RestController
@RequestMapping("/transport/product")
@Validated
public class ProductController {
    @Resource private ProductService productService;

    @PostMapping("/create")
    @Operation(summary = "创建商品")
    @PreAuthorize("@ss.hasPermission('transport:product:create')")
    public CommonResult<Long> create(@Valid @RequestBody ProductCreateReqVO reqVO) {
        return success(productService.create(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新商品")
    @PreAuthorize("@ss.hasPermission('transport:product:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody ProductUpdateReqVO reqVO) {
        productService.update(reqVO); return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除商品")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:product:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        productService.delete(id); return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得商品")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:product:query')")
    public CommonResult<ProductRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(productService.get(id), ProductRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得商品分页")
    @PreAuthorize("@ss.hasPermission('transport:product:query')")
    public CommonResult<PageResult<ProductRespVO>> page(@Valid ProductPageReqVO reqVO) {
        return success(BeanUtils.toBean(productService.getPage(reqVO), ProductRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得商品精简列表")
    public CommonResult<List<ProductSimpleRespVO>> simpleList() {
        return success(BeanUtils.toBean(productService.getSimpleList(), ProductSimpleRespVO.class));
    }
}
