package cn.iocoder.yudao.module.transport.controller.admin.transport.notice;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.notice.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.notice.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 平台公告")
@RestController
@RequestMapping("/transport/notice")
@Validated
public class NoticeController {
    @Resource private NoticeService noticeService;

    @PostMapping("/create")
    @Operation(summary = "创建平台公告")
    @PreAuthorize("@ss.hasPermission('transport:notice:create')")
    public CommonResult<Long> create(@Valid @RequestBody NoticeCreateReqVO reqVO) {
        return success(noticeService.create(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新平台公告")
    @PreAuthorize("@ss.hasPermission('transport:notice:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody NoticeUpdateReqVO reqVO) {
        noticeService.update(reqVO); return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除平台公告")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:notice:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        noticeService.delete(id); return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得平台公告")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:notice:query')")
    public CommonResult<NoticeRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(noticeService.get(id), NoticeRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得平台公告分页")
    @PreAuthorize("@ss.hasPermission('transport:notice:query')")
    public CommonResult<PageResult<NoticeRespVO>> page(@Valid NoticePageReqVO reqVO) {
        return success(BeanUtils.toBean(noticeService.getPage(reqVO), NoticeRespVO.class));
    }
}
