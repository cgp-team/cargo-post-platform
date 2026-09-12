package cn.iocoder.yudao.module.transport.controller.admin.transport.topology;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.topology.vo.OrderTopologyRespVO;
import cn.iocoder.yudao.module.transport.service.dispatch.TransportTopologyService;
import cn.iocoder.yudao.module.transport.service.dispatch.LegConflictService;
import cn.iocoder.yudao.module.transport.service.dispatch.MultiLegService;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportLegDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 运输拓扑")
@RestController
@RequestMapping("/transport/topology")
@Validated
public class TransportTopologyController {

    @Resource
    private TransportTopologyService transportTopologyService;
    @Resource
    private LegConflictService legConflictService;
    @Resource
    private MultiLegService multiLegService;

    @GetMapping("/order")
    @Operation(summary = "按订单获得运输拓扑（订单+方案+运输段+交接+候选解释+时间线）")
    @Parameter(name = "orderId", description = "运输订单编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:topology:query')")
    public CommonResult<OrderTopologyRespVO> byOrder(@RequestParam("orderId") Long orderId) {
        return success(transportTopologyService.getByOrderId(orderId));
    }

    @GetMapping("/plan")
    @Operation(summary = "按运输方案获得运输拓扑（调度结果可视化）")
    @Parameter(name = "planId", description = "调度方案编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:topology:query')")
    public CommonResult<OrderTopologyRespVO> byPlan(@RequestParam("planId") Long planId) {
        return success(transportTopologyService.getByPlanId(planId));
    }

    @GetMapping("/conflict-check")
    @Operation(summary = "车辆/司机时段冲突校验（后台手工分配前重新校验，不相信前端，需求 §52）")
    public CommonResult<Boolean> conflictCheck(
            @RequestParam(value = "vehicleId", required = false) Long vehicleId,
            @RequestParam(value = "driverId", required = false) Long driverId,
            @RequestParam("start") @org.springframework.format.annotation.DateTimeFormat(
                    pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start,
            @RequestParam("end") @org.springframework.format.annotation.DateTimeFormat(
                    pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end,
            @RequestParam(value = "excludeLegId", required = false) Long excludeLegId) {
        legConflictService.assertNoConflict(vehicleId, driverId, start, end, java.util.List.of(), excludeLegId);
        return success(true);
    }

    @PostMapping("/replan-leg")
    @Operation(summary = "异常重调度：只重新规划受影响的那一段（需求 §77/§108），已完成段不动")
    @Parameter(name = "legId", description = "运输段编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:topology:query')")
    public CommonResult<TransportLegDO> replanLeg(@RequestParam("legId") Long legId,
                                                  @RequestParam(value = "remark", required = false) String remark) {
        return success(multiLegService.replanLeg(legId, remark));
    }

}
