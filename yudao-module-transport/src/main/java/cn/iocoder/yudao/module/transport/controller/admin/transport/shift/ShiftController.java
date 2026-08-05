package cn.iocoder.yudao.module.transport.controller.admin.transport.shift;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.shift.ShiftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 班次管理")
@RestController
@RequestMapping("/transport/shift")
@Validated
public class ShiftController {
    @Resource private ShiftService shiftService;

    @PostMapping("/create")
    @Operation(summary = "创建班次")
    @PreAuthorize("@ss.hasPermission('transport:shift:create')")
    public CommonResult<Long> create(@Valid @RequestBody ShiftCreateReqVO reqVO) {
        return success(shiftService.create(reqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新班次")
    @PreAuthorize("@ss.hasPermission('transport:shift:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody ShiftUpdateReqVO reqVO) {
        shiftService.update(reqVO); return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除班次")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:shift:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) {
        shiftService.delete(id); return success(true);
    }

    @GetMapping("/get")
    @Operation(summary = "获得班次")
    @Parameter(name = "id", description = "编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:shift:query')")
    public CommonResult<ShiftRespVO> get(@RequestParam("id") Long id) {
        return success(BeanUtils.toBean(shiftService.get(id), ShiftRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得班次分页")
    @PreAuthorize("@ss.hasPermission('transport:shift:query')")
    public CommonResult<PageResult<ShiftRespVO>> page(@Valid ShiftPageReqVO reqVO) {
        return success(BeanUtils.toBean(shiftService.getPage(reqVO), ShiftRespVO.class));
    }

    @GetMapping("/simple-list")
    @Operation(summary = "获得班次精简列表")
    public CommonResult<List<ShiftSimpleRespVO>> simpleList() {
        return success(BeanUtils.toBean(shiftService.getSimpleList(), ShiftSimpleRespVO.class));
    }
}
