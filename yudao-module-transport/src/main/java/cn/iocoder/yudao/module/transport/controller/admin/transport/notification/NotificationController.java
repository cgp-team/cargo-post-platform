package cn.iocoder.yudao.module.transport.controller.admin.transport.notification;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.idempotent.core.annotation.Idempotent;
import cn.iocoder.yudao.framework.idempotent.core.keyresolver.impl.UserIdempotentKeyResolver;
import java.util.concurrent.TimeUnit;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo.NotificationPageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo.NotificationRespVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notification.vo.NotificationSendReqVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.TransportUserNotificationDO;
import cn.iocoder.yudao.module.transport.service.notification.UserNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 用户通知")
@RestController
@RequestMapping("/transport/notification")
@Validated
public class NotificationController {

    @Resource
    private UserNotificationService userNotificationService;

    @GetMapping("/page")
    @Operation(summary = "获得用户通知分页")
    @PreAuthorize("@ss.hasPermission('transport:notification:query')")
    public CommonResult<PageResult<NotificationRespVO>> page(@Valid NotificationPageReqVO reqVO) {
        PageResult<TransportUserNotificationDO> pageResult =
                userNotificationService.getPage(reqVO);
        return success(BeanUtils.toBean(pageResult, NotificationRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获得用户通知")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:notification:query')")
    public CommonResult<NotificationRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(userNotificationService.get(id), NotificationRespVO.class));
    }

    // #92：这张表里 USER/DRIVER/ADMIN 三类接收方共用编号空间，原来后台没有任何一格问得出
    //   "管理员还有几条没处理"，告警写得进去、查不出来，等于没有告警队列。
    @GetMapping("/admin-unread-count")
    @Operation(summary = "管理员未处理告警分档计数（total + 每个 level）")
    @PreAuthorize("@ss.hasPermission('transport:notification:query')")
    public CommonResult<java.util.Map<String, Long>> adminUnreadCount() {
        return success(userNotificationService.getAdminUnreadCount());
    }

    @PutMapping("/mark-admin-read")
    @Operation(summary = "把一条管理员告警标为已处理（记 readTime；表里没有操作人列，所以不记是谁点的）")
    @Parameter(name = "id", description = "通知编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:notification:send')")
    public CommonResult<Boolean> markAdminRead(@RequestParam("id") Long id) {
        userNotificationService.markAdminRead(id);
        return success(true);
    }

    @PostMapping("/send")
    @Operation(summary = "发送用户通知")
    @PreAuthorize("@ss.hasPermission('transport:notification:send')")
    @Idempotent(timeout = 10, timeUnit = TimeUnit.SECONDS, keyResolver = UserIdempotentKeyResolver.class,
            message = "这条通知正在发送中，请勿重复提交（重复提交会给用户多发一条）")
    public CommonResult<Long> send(@Valid @RequestBody NotificationSendReqVO reqVO) {
        return success(userNotificationService.send(reqVO.getUserId(), null, reqVO.getTitle(),
                reqVO.getContent(), reqVO.getOrderId()));
    }

}
