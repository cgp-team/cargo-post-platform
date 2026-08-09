package cn.iocoder.yudao.module.transport.controller.admin.dispatch;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.DispatchPlanDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.service.dispatch.DispatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 班次调度")
@RestController
@RequestMapping("/transport/dispatch")
@Validated
public class DispatchController {

    @Resource private DispatchService dispatchService;

    @GetMapping("/order-pool/page")
    @Operation(summary = "获得调度订单池分页")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:query')")
    public CommonResult<PageResult<TransportOrderDO>> getOrderPoolPage(@Valid DispatchPoolPageReqVO reqVO) {
        return success(dispatchService.getOrderPoolPage(reqVO));
    }

    @PostMapping("/order-pool/collect")
    @Operation(summary = "订单归集入池")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:collect')")
    public CommonResult<Integer> collectOrders(@Valid @RequestBody DispatchCollectReqVO reqVO) {
        return success(dispatchService.collectOrders(reqVO));
    }

    @PostMapping("/plan/manual")
    @Operation(summary = "手工派单")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:manual-plan')")
    public CommonResult<Long> createManualPlan(@Valid @RequestBody DispatchManualPlanReqVO reqVO) {
        return success(dispatchService.createManualPlan(reqVO));
    }

    @PostMapping("/plan/smart")
    @Operation(summary = "智能派单")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:smart-plan')")
    public CommonResult<Long> createSmartPlan(@Valid @RequestBody DispatchSmartPlanReqVO reqVO) {
        return success(dispatchService.createSmartPlan(reqVO));
    }

    @GetMapping("/plan/page")
    @Operation(summary = "获得调度方案分页")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:query')")
    public CommonResult<PageResult<DispatchPlanDO>> getPlanPage(@Valid DispatchPlanPageReqVO reqVO) {
        return success(dispatchService.getPlanPage(reqVO));
    }

    @GetMapping("/plan/get")
    @Operation(summary = "获得调度方案")
    @Parameter(name = "id", description = "方案编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:dispatch:query')")
    public CommonResult<DispatchPlanRespVO> getPlan(@RequestParam("id") Long id) {
        return success(dispatchService.getPlan(id));
    }

    @PutMapping("/plan/review")
    @Operation(summary = "调度方案审核")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:review')")
    public CommonResult<Boolean> reviewPlan(@Valid @RequestBody DispatchPlanReviewReqVO reqVO) {
        dispatchService.reviewPlan(reqVO);
        return success(true);
    }

    @PostMapping("/departure-check")
    @Operation(summary = "发车核验")
    @PreAuthorize("@ss.hasPermission('transport:dispatch:check')")
    public CommonResult<Boolean> departureCheck(@Valid @RequestBody DispatchCheckReqVO reqVO) {
        dispatchService.departureCheck(reqVO);
        return success(true);
    }

}
