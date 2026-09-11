package cn.iocoder.yudao.module.transport.controller.admin.transport.notification;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
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

    @PostMapping("/send")
    @Operation(summary = "发送用户通知")
    @PreAuthorize("@ss.hasPermission('transport:notification:send')")
    public CommonResult<Long> send(@Valid @RequestBody NotificationSendReqVO reqVO) {
        return success(userNotificationService.send(reqVO.getUserId(), null, reqVO.getTitle(),
                reqVO.getContent(), reqVO.getOrderId()));
    }

}
