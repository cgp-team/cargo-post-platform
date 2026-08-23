package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.service.transport.vehicle.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="Management - Vehicle")
@RestController
@RequestMapping("/transport/vehicle")
@Validated
public class VehicleController {
    @Resource private VehicleService vehicleService;

    @PostMapping("/create")
    @Operation(summary="Create")
    @PreAuthorize("@ss.hasPermission('transport:vehicle:create')")
    public CommonResult<Long> create(@Valid @RequestBody VehicleCreateReqVO reqVO) { return success(vehicleService.create(reqVO)); }

    @PutMapping("/update")
    @Operation(summary="Update")
    @PreAuthorize("@ss.hasPermission('transport:vehicle:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody VehicleUpdateReqVO reqVO) { vehicleService.update(reqVO); return success(true); }

    @DeleteMapping("/delete")
    @Operation(summary="Delete")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:vehicle:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) { vehicleService.delete(id); return success(true); }

    @GetMapping("/get")
    @Operation(summary="Get")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:vehicle:query')")
    public CommonResult<VehicleRespVO> get(@RequestParam("id") Long id) { return success(BeanUtils.toBean(vehicleService.get(id), VehicleRespVO.class)); }

    @GetMapping("/page")
    @Operation(summary="Get Page")
    @PreAuthorize("@ss.hasPermission('transport:vehicle:query')")
    public CommonResult<PageResult<VehicleRespVO>> page(@Valid VehiclePageReqVO reqVO) { return success(BeanUtils.toBean(vehicleService.getPage(reqVO), VehicleRespVO.class)); }

    @GetMapping("/simple-list")
    @Operation(summary="Get Simple List")
    public CommonResult<java.util.List<VehicleSimpleRespVO>> simpleList() {
        return success(BeanUtils.toBean(vehicleService.getSimpleList(), VehicleSimpleRespVO.class));
    }

    @GetMapping("/expiring-list")
    @Operation(summary="Get Expiring List")
    @Parameter(name="days", description="Days to expiry (including expired)")
    @PreAuthorize("@ss.hasPermission('transport:vehicle:query')")
    public CommonResult<java.util.List<VehicleRespVO>> expiringList(@RequestParam(value="days", defaultValue="30") Integer days) {
        return success(BeanUtils.toBean(vehicleService.getExpiringList(days), VehicleRespVO.class));
    }
}
