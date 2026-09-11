package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.controller.admin.transport.topology.vo.OrderTopologyRespVO;

/**
 * 订单运输拓扑聚合服务：一次返回订单 + 方案 + 运输段 + 换乘交接 + 候选解释 + 事件时间线。
 */
public interface TransportTopologyService {

    /** 按订单聚合运输拓扑（订单不存在抛 ORDER_NOT_EXISTS） */
    OrderTopologyRespVO getByOrderId(Long orderId);

    /** 按方案聚合运输拓扑（后台调度结果可视化） */
    OrderTopologyRespVO getByPlanId(Long planId);

}
