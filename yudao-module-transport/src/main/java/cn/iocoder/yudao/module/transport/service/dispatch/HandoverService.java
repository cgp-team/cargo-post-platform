package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.transport.controller.admin.transport.handover.vo.HandoverPageReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportHandoverDO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 货物交接服务：多段联运在换乘站把货从上一段司机交到下一段司机，交接记录闭环后推进订单/段状态。
 */
public interface HandoverService {

    /**
     * 创建交接记录（交出方发起）：交接站点取来源段的目的站。
     *
     * @return 交接记录编号
     */
    Long createHandover(Long orderId, Long legFromId, Long legToId, Integer itemCount,
                        BigDecimal weightKg, String remark);

    /** 前序司机到达换乘站：交接置"前序已到达"，通知后序司机来接货、用户换乘中（需求 §60） */
    Long markSourceArrived(Long orderId, Long legFromId);

    /** 接收司机到场，开始交接（要求前序已到达，需求 §9/§61） */
    void startHandover(Long handoverId, Long driverId);

    /** 接收司机确认交接（核验件数/拍照），推进 legFrom=已交接、legTo=已分配/运输中、订单=部分完成/完成 */
    void confirmHandover(Long handoverId, Long toDriverId, String photoUrl);

    /** 标记交接争议（件数不符/破损），订单记异常事件，需人工介入 */
    void disputeHandover(Long handoverId, String remark);

    /** 交接超时（前序超时未到）→ 置超时 + 订单异常 + 后台告警（需求 §108） */
    void timeoutHandover(Long handoverId, String remark);

    /** 按编号查交接记录（不存在抛 HANDOVER_NOT_EXISTS） */
    TransportHandoverDO get(Long id);

    /** 按来源运输段/目的运输段查交接（司机端按段定位交接任务） */
    TransportHandoverDO getByLeg(Long legId);

    /** 按方案查交接记录 */
    List<TransportHandoverDO> getByPlan(Long planId);

    /** 管理端分页 */
    PageResult<TransportHandoverDO> getPage(HandoverPageReqVO reqVO);

    /** 按订单查交接记录 */
    List<TransportHandoverDO> getByOrder(Long orderId);

    /** 某司机待确认的交接（交出或接收） */
    List<TransportHandoverDO> getPendingByDriver(Long driverId);

}
