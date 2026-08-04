package cn.iocoder.yudao.module.transport.controller.admin.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="Management - Driver")
@RestController
@RequestMapping("/transport/driver")
@Validated
public class DriverController {
    @Resource private DriverService driverService;

    @PostMapping("/create")
    @Operation(summary="Create")
    @PreAuthorize("@ss.hasPermission('transport:driver:create')")
    public CommonResult<Long> create(@Valid @RequestBody DriverCreateReqVO reqVO) { return success(driverService.create(reqVO)); }

    @PutMapping("/update")
    @Operation(summary="Update")
    @PreAuthorize("@ss.hasPermission('transport:driver:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody DriverUpdateReqVO reqVO) { driverService.update(reqVO); return success(true); }

    @DeleteMapping("/delete")
    @Operation(summary="Delete")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:driver:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) { driverService.delete(id); return success(true); }

    @GetMapping("/get")
    @Operation(summary="Get")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:driver:query')")
    public CommonResult<DriverRespVO> get(@RequestParam("id") Long id) { return success(BeanUtils.toBean(driverService.get(id), DriverRespVO.class)); }

    @GetMapping("/page")
    @Operation(summary="Get Page")
    @PreAuthorize("@ss.hasPermission('transport:driver:query')")
    public CommonResult<PageResult<DriverRespVO>> page(@Valid DriverPageReqVO reqVO) { return success(BeanUtils.toBean(driverService.getPage(reqVO), DriverRespVO.class)); }
}
