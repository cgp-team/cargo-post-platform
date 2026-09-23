package cn.iocoder.yudao.module.transport.controller.app.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverAppService;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum;
import cn.iocoder.yudao.module.transport.controller.app.transport.notification.vo.AppNotificationRespVO;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 司机端")
@RestController
@RequestMapping("/transport/driver")
@Validated
public class AppDriverController {

    @Resource private DriverAppService driverAppService;
    @Resource private UserNotificationService userNotificationService;
    @Resource private OrderEventService orderEventService;

    @GetMapping("/profile")
    @Operation(summary = "司机档案（按登录会员识别身份）")
    public CommonResult<AppDriverProfileRespVO> profile() {
        return success(driverAppService.profile());
    }

    @GetMapping("/shifts")
    @Operation(summary = "今日班次与经停站点")
    public CommonResult<List<AppDriverShiftRespVO>> shifts() {
        return success(driverAppService.shifts());
    }

    @GetMapping("/pickups")
    @Operation(summary = "待装车任务")
    public CommonResult<List<AppDriverPickupRespVO>> pickups() {
        return success(driverAppService.pickups());
    }

    @GetMapping("/earnings")
    @Operation(summary = "运营统计")
    public CommonResult<AppDriverEarningsRespVO> earnings() {
        return success(driverAppService.earnings());
    }

    @GetMapping("/tasks")
    @Operation(summary = "调度任务（算法派单结果；按登录会员识别司机，driverId 仅做一致性校验）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<List<AppDriverTaskRespVO>> tasks(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.tasks(driverId));
    }

    @GetMapping("/route")
    @Operation(summary = "司机路线（完整任务段有序经停 + 真实道路 polyline + 偏航判定，Phase 9 地图数据）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<AppDriverRouteRespVO> route(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.getRoute(driverId));
    }

    @PostMapping("/depart")
    @Operation(summary = "发车（创建班次执行，货运订单推进已发车）")
    public CommonResult<Boolean> depart(@Valid @RequestBody AppDriverDepartReqVO reqVO) {
        driverAppService.depart(reqVO);
        return success(true);
    }

    @PostMapping("/arrive")
    @Operation(summary = "到站（更新当前站点，终点站到达完成班次）")
    public CommonResult<Boolean> arrive(@Valid @RequestBody AppDriverArriveReqVO reqVO) {
        driverAppService.arrive(reqVO);
        return success(true);
    }

    @PostMapping("/pickup-confirm")
    @Operation(summary = "确认装车（货运订单推进已发车）")
    public CommonResult<Boolean> pickupConfirm(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.pickupConfirm(reqVO);
        return success(true);
    }

    @PostMapping("/deliver")
    @Operation(summary = "确认送达（货运订单推进已完成）")
    public CommonResult<Boolean> deliver(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.deliver(reqVO);
        return success(true);
    }

    @PostMapping("/pickup-verify")
    @Operation(summary = "取件核销（邮快件收件人取件，司机确认，校验取件码）")
    public CommonResult<Boolean> pickupVerify(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.pickupVerify(reqVO);
        return success(true);
    }

    @PostMapping("/product-load")
    @Operation(summary = "商城订单装车确认（司机拍照核验凭证，订单保持已发货/配送中）")
    public CommonResult<Boolean> productLoad(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.productLoad(reqVO);
        return success(true);
    }

    @PostMapping("/product-deliver")
    @Operation(summary = "商城订单妥投完成（司机交付凭证，订单转已完成，用户端可见）")
    public CommonResult<Boolean> productDeliver(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.productDeliver(reqVO);
        return success(true);
    }

    @PostMapping("/location")
    @Operation(summary = "上报车辆实时位置")
    public CommonResult<Boolean> reportLocation(@Valid @RequestBody AppDriverLocationReqVO reqVO) {
        driverAppService.reportLocation(reqVO);
        return success(true);
    }

    @GetMapping("/handovers")
    @Operation(summary = "待确认的货物交接任务（多段联运换乘站交接）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<List<AppDriverHandoverRespVO>> handovers(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.handovers(driverId));
    }

    @PostMapping("/handover/confirm")
    @Operation(summary = "确认货物交接（拍照核验，推进运输段与订单状态）")
    public CommonResult<Boolean> handoverConfirm(@Valid @RequestBody AppDriverHandoverConfirmReqVO reqVO) {
        driverAppService.handoverConfirm(reqVO);
        return success(true);
    }

    @GetMapping("/legs")
    @Operation(summary = "我的运输段进度（多段联运）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<List<AppDriverLegRespVO>> legs(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.legs(driverId));
    }

    @GetMapping("/current-leg")
    @Operation(summary = "当前运输段（司机任务详情，只返回该司机自己的段）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<AppDriverLegRespVO> currentLeg(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.currentLeg(driverId));
    }

    @PostMapping("/leg/accept")
    @Operation(summary = "接受任务（已分配 → 司机已接单）")
    public CommonResult<Boolean> legAccept(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("accept", reqVO);
        return success(true);
    }

    @PostMapping("/leg/navigate")
    @Operation(summary = "开始导航（前往取货点）")
    public CommonResult<Boolean> legNavigate(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("navigate", reqVO);
        return success(true);
    }

    @PostMapping("/leg/arrive-origin")
    @Operation(summary = "已到达取货点")
    public CommonResult<Boolean> legArriveOrigin(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("arrive-origin", reqVO);
        return success(true);
    }

    @PostMapping("/leg/load")
    @Operation(summary = "开始装货")
    public CommonResult<Boolean> legLoad(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("load", reqVO);
        return success(true);
    }

    @PostMapping("/leg/start")
    @Operation(summary = "开始运输（装货完成，发车）")
    public CommonResult<Boolean> legStart(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("start", reqVO);
        return success(true);
    }

    @PostMapping("/leg/arrive-dest")
    @Operation(summary = "已到达终点（需换乘则触发交接，最终段进入派送）")
    public CommonResult<Boolean> legArriveDest(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("arrive-dest", reqVO);
        return success(true);
    }

    @PostMapping("/leg/handover-start")
    @Operation(summary = "开始交接（接收方到场核货）")
    public CommonResult<Boolean> legHandoverStart(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("handover-start", reqVO);
        return success(true);
    }

    @PostMapping("/leg/handover-confirm")
    @Operation(summary = "确认接货（交接完成，下一段自动开始运输）")
    public CommonResult<Boolean> legHandoverConfirm(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("handover-confirm", reqVO);
        return success(true);
    }

    @PostMapping("/leg/complete")
    @Operation(summary = "完成配送/取货（最终段完成，全部段完成后订单完成）")
    public CommonResult<Boolean> legComplete(@Valid @RequestBody AppDriverLegActionReqVO reqVO) {
        driverAppService.legAction("complete", reqVO);
        return success(true);
    }

    @GetMapping("/messages")
    @Operation(summary = "司机消息中心分页")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<PageResult<AppNotificationRespVO>> messages(
            @RequestParam("driverId") Long driverId, PageParam pageParam,
            @RequestParam(value = "readStatus", required = false) Integer readStatus) {
        driverAppService.requireCurrentDriver(driverId); // 归属校验：防越权查看他人司机通知（订单号/收件人手机等 PII 泄露）
        PageResult<AppNotificationRespVO> result = BeanUtils.toBean(
                userNotificationService.getDriverPage(driverId, readStatus, pageParam),
                AppNotificationRespVO.class, vo -> vo.setEventTypeName(orderEventService.typeName(vo.getEventType())));
        return success(result);
    }

    @GetMapping("/messages/unread-count")
    @Operation(summary = "司机未读消息数")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<Long> messagesUnreadCount(@RequestParam("driverId") Long driverId) {
        driverAppService.requireCurrentDriver(driverId); // 归属校验：防越权探测他人司机未读数
        return success(userNotificationService.getDriverUnreadCount(driverId));
    }

    @PutMapping("/messages/read")
    @Operation(summary = "标记司机消息已读")
    @Parameter(name = "id", description = "通知编号", required = true)
    public CommonResult<Boolean> messageRead(@RequestParam("id") Long id,
                                             @RequestParam("driverId") Long driverId) {
        userNotificationService.markAsRead(id, NotificationRecipientTypeEnum.DRIVER, driverId);
        return success(true);
    }

    @GetMapping("/position")
    @Operation(summary = "司机车辆当前位置（真实上报 REAL）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<AppDriverPositionRespVO> position(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.getPosition(driverId));
    }
}
