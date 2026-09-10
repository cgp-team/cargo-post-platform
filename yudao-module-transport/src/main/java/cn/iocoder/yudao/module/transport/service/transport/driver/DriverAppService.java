package cn.iocoder.yudao.module.transport.service.transport.driver;

import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.*;

import java.util.List;

/**
 * 司机端 App 聚合查询 Service。
 */
public interface DriverAppService {

    /** 司机档案（按登录会员识别身份，含绑定车辆与运力） */
    AppDriverProfileRespVO profile();

    /** 今日班次与经停站点序列 */
    List<AppDriverShiftRespVO> shifts();

    /** 待装车任务（待处理货运订单） */
    List<AppDriverPickupRespVO> pickups();

    /** 运营统计（班次/货运订单） */
    AppDriverEarningsRespVO earnings();

    /** 调度任务（算法派单结果；司机身份从登录态解析，driverId 仅做一致性校验） */
    List<AppDriverTaskRespVO> tasks(Long driverId);

    /**
     * 司机路线（地图数据，Phase 9）：完整任务段的有序经停 + 真实道路 polyline。
     * 运营顺序来自 DispatchPlan，道路轨迹来自 RoadSegments（/api/v1/route，失败回退直线明确 euclidean）。
     * 返回当前车辆位置对规划 polyline 的偏航判定（>100m 标记 ROUTE_DEVIATED，只报警不自动改方案）。
     */
    AppDriverRouteRespVO getRoute(Long driverId);

    /** 发车：创建/复用当天班次执行记录并置在途，该司机名下已分配货运订单推进为已发车 */
    void depart(AppDriverDepartReqVO reqVO);

    /** 到站：更新执行记录当前站点；到达线路终点站时执行记录置已完成 */
    void arrive(AppDriverArriveReqVO reqVO);

    /** 确认装车：货运订单推进为已发车 */
    void pickupConfirm(AppDriverOrderActionReqVO reqVO);

    /** 确认送达：货运订单推进为已完成 */
    void deliver(AppDriverOrderActionReqVO reqVO);

    /** 取件核销：邮快件收件人取件，司机确认（校验取件码，主表 3→4 + 子表已取件） */
    void pickupVerify(AppDriverOrderActionReqVO reqVO);

    /** 司机端：商城订单装车确认（拍照核验凭证，订单仍为已发货/配送中） */
    void productLoad(AppDriverOrderActionReqVO reqVO);

    /** 司机端：商城订单妥投完成（交付凭证，订单转已完成，用户端可见） */
    void productDeliver(AppDriverOrderActionReqVO reqVO);

    /** 上报车辆实时位置（按车辆 upsert） */
    void reportLocation(AppDriverLocationReqVO reqVO);

    /**
     * 司机车辆当前位置：真实上报位置（REAL）与模拟运营引擎位置（SIMULATED）都返回，
     * 前端按「模拟模式」选择消费哪一路。
     */
    AppDriverPositionRespVO getPosition(Long driverId);
}
