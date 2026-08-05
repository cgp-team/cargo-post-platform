package cn.iocoder.yudao.module.transport.controller.admin.transport.order;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.order.TransportOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 订单管理")
@RestController
@RequestMapping("/transport/order")
@Validated
public class TransportOrderController {
    @Resource private TransportOrderService orderService;

    @PostMapping("/create")
    @Operation(summary = "创建订单")
    @PreAuthorize("@ss.hasPermission('transport:order:create')")
    public CommonResult<Long> create(@Valid @RequestBody TransportOrderCreateReqVO reqVO) {
        return success(orderService.create(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新订单")
    @PreAuthorize("@ss.hasPermission('transport:order:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody TransportOrderUpdateReqVO reqVO) {
        orderService.update(reqVO); return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除订单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:order:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        orderService.delete(id); return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得订单")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:order:query')")
    public CommonResult<TransportOrderRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(orderService.get(id), TransportOrderRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得订单分页")
    @PreAuthorize("@ss.hasPermission('transport:order:query')")
    public CommonResult<PageResult<TransportOrderRespVO>> page(@Valid TransportOrderPageReqVO reqVO) {
        return success(BeanUtils.toBean(orderService.getPage(reqVO), TransportOrderRespVO.class));
    }
}
