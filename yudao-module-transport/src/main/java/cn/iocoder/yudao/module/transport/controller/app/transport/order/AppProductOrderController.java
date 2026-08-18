package cn.iocoder.yudao.module.transport.controller.app.transport.order;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo.ProductOrderPageReqVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.order.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.ProductOrderItemDO;
import cn.iocoder.yudao.module.transport.enums.transport.ProductOrderStatusEnum;
import cn.iocoder.yudao.module.transport.service.transport.order.ProductOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 商城订单")
@RestController
@RequestMapping("/transport/product-order")
@Validated
public class AppProductOrderController {

    @Resource private ProductOrderService productOrderService;

    @PostMapping("/create")
    @Operation(summary = "创建商城订单")
    public CommonResult<AppProductOrderCreateRespVO> create(@Valid @RequestBody AppProductOrderCreateReqVO reqVO) {
        Long orderId = productOrderService.createOrder(getLoginUserId(), reqVO);
        AppProductOrderCreateRespVO respVO = new AppProductOrderCreateRespVO();
        respVO.setId(orderId);
        respVO.setOrderNo(productOrderService.get(orderId).getOrderNo());
        return success(respVO);
    }

    @GetMapping("/page")
    @Operation(summary = "我的订单分页")
    public CommonResult<PageResult<AppProductOrderRespVO>> page(@Valid ProductOrderPageReqVO reqVO) {
        PageResult<ProductOrderDO> pageResult = productOrderService.getMyPage(getLoginUserId(), reqVO);
        List<AppProductOrderRespVO> list = pageResult.getList().stream()
                .map(order -> toAppVO(order))
                .toList();
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @PutMapping("/cancel")
    @Operation(summary = "取消订单")
    @Parameter(name = "id", description = "订单编号", required = true)
    public CommonResult<Boolean> cancel(@RequestParam("id") Long id) {
        productOrderService.cancel(getLoginUserId(), id);
        return success(true);
    }

    @GetMapping("/trace")
    @Operation(summary = "订单溯源（承运车辆/班次/线路站点/当天轨迹/最新位置；未发车返回空语义）")
    @Parameter(name = "id", description = "订单编号", required = true)
    public CommonResult<AppProductOrderTraceRespVO> trace(@RequestParam("id") Long id) {
        return success(productOrderService.getTrace(getLoginUserId(), id));
    }

    private AppProductOrderRespVO toAppVO(ProductOrderDO order) {
        AppProductOrderRespVO vo = BeanUtils.toBean(order, AppProductOrderRespVO.class);
        vo.setStatusName(ProductOrderStatusEnum.nameOf(order.getStatus()));
        List<ProductOrderItemDO> items = productOrderService.getItemsByOrderId(order.getId());
        vo.setItems(BeanUtils.toBean(items, AppProductOrderItemRespVO.class));
        return vo;
    }
}
