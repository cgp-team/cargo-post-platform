package cn.iocoder.yudao.module.transport.controller.app.transport.notice;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.app.transport.notice.vo.AppNoticeRespVO;
import cn.iocoder.yudao.module.transport.service.transport.notice.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 平台公告")
@RestController
@RequestMapping("/transport/notice")
@Validated
public class AppNoticeController {
    @Resource private NoticeService noticeService;

    @GetMapping("/list")
    @Operation(summary = "获得上架公告列表")
    @PermitAll
    public CommonResult<List<AppNoticeRespVO>> list() {
        return success(BeanUtils.toBean(noticeService.getOnShelfList(), AppNoticeRespVO.class));
    }
}
