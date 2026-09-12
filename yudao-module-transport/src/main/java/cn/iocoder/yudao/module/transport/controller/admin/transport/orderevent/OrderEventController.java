package cn.iocoder.yudao.module.transport.controller.admin.transport.orderevent;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.orderevent.vo.OrderEventRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportOrderEventDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.order.TransportOrderDO;
import cn.iocoder.yudao.module.transport.dal.mysql.order.TransportOrderMapper;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 订单事件时间线")
@RestController
@RequestMapping("/transport/order-event")
@Validated
public class OrderEventController {

    @Resource
    private OrderEventService orderEventService;
    @Resource
    private TransportOrderMapper orderMapper;

    @GetMapping("/list-by-order")
    @Operation(summary = "按订单获得事件时间线")
    @Parameter(name = "orderId", description = "运输订单编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:order:query')")
    public CommonResult<List<OrderEventRespVO>> listByOrder(@RequestParam("orderId") Long orderId) {
        List<TransportOrderEventDO> timeline = orderEventService.getTimeline(orderId);
        TransportOrderDO order = orderMapper.selectById(orderId);
        List<OrderEventRespVO> list = BeanUtils.toBean(timeline, OrderEventRespVO.class, vo -> {
            vo.setEventTypeName(orderEventService.typeName(vo.getEventType()));
            vo.setOrderNo(order != null ? order.getOrderNo() : null);
        });
        return success(list);
    }

}
