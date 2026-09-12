package cn.iocoder.yudao.module.transport.controller.app.transport.notification;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.app.transport.notification.vo.AppNotificationRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import cn.iocoder.yudao.module.transport.enums.notification.NotificationRecipientTypeEnum;
import cn.iocoder.yudao.module.transport.service.order.OrderEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "用户 APP - 消息通知中心")
@RestController
@RequestMapping("/transport/notification")
@Validated
public class AppNotificationController {

    @Resource
    private UserNotificationService userNotificationService;
    @Resource
    private OrderEventService orderEventService;

    @GetMapping("/page")
    @Operation(summary = "我的消息分页（readStatus 可选：0未读 1已读）")
    @Parameter(name = "readStatus", description = "阅读状态：0未读 1已读")
    public CommonResult<PageResult<AppNotificationRespVO>> page(PageParam pageParam,
                                                               @RequestParam(value = "readStatus", required = false) Integer readStatus) {
        PageResult<TransportUserNotificationDO> pageResult =
                userNotificationService.getMyPage(getLoginUserId(), readStatus, pageParam);
        return success(BeanUtils.toBean(pageResult, AppNotificationRespVO.class, vo ->
                vo.setEventTypeName(orderEventService.typeName(vo.getEventType()))));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "我的未读消息数（消息中心红点）")
    public CommonResult<Long> unreadCount() {
        return success(userNotificationService.getUnreadCount(getLoginUserId()));
    }

    @PutMapping("/read")
    @Operation(summary = "标记单条消息已读")
    @Parameter(name = "id", description = "通知编号", required = true)
    public CommonResult<Boolean> read(@RequestParam("id") Long id) {
        userNotificationService.markAsRead(id, NotificationRecipientTypeEnum.USER, getLoginUserId());
        return success(true);
    }

    @PutMapping("/read-all")
    @Operation(summary = "全部标记已读（orderId 可选：仅标记该订单）")
    @Parameter(name = "orderId", description = "关联订单编号")
    public CommonResult<Boolean> readAll(@RequestParam(value = "orderId", required = false) Long orderId) {
        userNotificationService.markAllAsRead(getLoginUserId(), orderId);
        return success(true);
    }

}
