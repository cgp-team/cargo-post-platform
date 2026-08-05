package cn.iocoder.yudao.module.transport.controller.admin.transport.station;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.service.transport.station.StationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="Management - Station")
@RestController
@RequestMapping("/transport/station")
@Validated
public class StationController {
    @Resource private StationService stationService;

    @PostMapping("/create")
    @Operation(summary="Create")
    @PreAuthorize("@ss.hasPermission('transport:station:create')")
    public CommonResult<Long> create(@Valid @RequestBody StationCreateReqVO reqVO) { return success(stationService.create(reqVO)); }

    @PutMapping("/update")
    @Operation(summary="Update")
    @PreAuthorize("@ss.hasPermission('transport:station:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody StationUpdateReqVO reqVO) { stationService.update(reqVO); return success(true); }

    @DeleteMapping("/delete")
    @Operation(summary="Delete")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:station:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) { stationService.delete(id); return success(true); }

    @GetMapping("/get")
    @Operation(summary="Get")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:station:query')")
    public CommonResult<StationRespVO> get(@RequestParam("id") Long id) { return success(BeanUtils.toBean(stationService.get(id), StationRespVO.class)); }

    @GetMapping("/page")
    @Operation(summary="Get Page")
    @PreAuthorize("@ss.hasPermission('transport:station:query')")
    public CommonResult<PageResult<StationRespVO>> page(@Valid StationPageReqVO reqVO) { return success(BeanUtils.toBean(stationService.getPage(reqVO), StationRespVO.class)); }

    @GetMapping("/simple-list")
    @Operation(summary="Get Simple List")
    public CommonResult<java.util.List<StationSimpleRespVO>> simpleList() {
        return success(BeanUtils.toBean(stationService.getSimpleList(), StationSimpleRespVO.class));
    }
}
