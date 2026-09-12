package cn.iocoder.yudao.module.transport.controller.admin.transport.route;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo.*;
import cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO;
import cn.iocoder.yudao.module.transport.service.transport.route.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name="Management - Route")
@RestController
@RequestMapping("/transport/route")
@Validated
public class RouteController {
    @Resource private RouteService routeService;

    @PostMapping("/create")
    @Operation(summary="Create")
    @PreAuthorize("@ss.hasPermission('transport:route:create')")
    public CommonResult<Long> create(@Valid @RequestBody RouteCreateReqVO reqVO) { return success(routeService.create(reqVO)); }

    @PutMapping("/update")
    @Operation(summary="Update")
    @PreAuthorize("@ss.hasPermission('transport:route:update')")
    public CommonResult<Boolean> update(@Valid @RequestBody RouteUpdateReqVO reqVO) { routeService.update(reqVO); return success(true); }

    @DeleteMapping("/delete")
    @Operation(summary="Delete")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:route:delete')")
    public CommonResult<Boolean> delete(@RequestParam("id") Long id) { routeService.delete(id); return success(true); }

    @GetMapping("/get")
    @Operation(summary="Get")
    @Parameter(name="id", description="ID", required=true)
    @PreAuthorize("@ss.hasPermission('transport:route:query')")
    public CommonResult<RouteRespVO> get(@RequestParam("id") Long id) { return success(BeanUtils.toBean(routeService.get(id), RouteRespVO.class)); }

    @GetMapping("/page")
    @Operation(summary="Get Page")
    @PreAuthorize("@ss.hasPermission('transport:route:query')")
    public CommonResult<PageResult<RouteRespVO>> page(@Valid RoutePageReqVO reqVO) { return success(BeanUtils.toBean(routeService.getPage(reqVO), RouteRespVO.class)); }

    @GetMapping("/simple-list")
    @Operation(summary="Get Simple List")
    public CommonResult<java.util.List<RouteSimpleRespVO>> simpleList() {
        return success(BeanUtils.toBean(routeService.getSimpleList(), RouteSimpleRespVO.class));
    }

    @GetMapping("/stations")
    @Operation(summary = "获得线路经停站点（站序，含站点名与经纬度）")
    @Parameter(name="routeId", description="线路编号", required=true)
    @PreAuthorize("@ss.hasPermission('transport:route:query')")
    public CommonResult<java.util.List<RouteStationRespVO>> stations(@RequestParam("routeId") Long routeId) {
        return success(routeService.getRouteStations(routeId));
    }

    @PutMapping("/stations")
    @Operation(summary = "保存线路经停站点序列（农村无现成路网时，自建站点按顺序拼成客货邮线路）")
    @PreAuthorize("@ss.hasPermission('transport:route:update')")
    public CommonResult<Boolean> saveStations(@Valid @RequestBody RouteStationSaveReqVO reqVO) {
        routeService.saveRouteStations(reqVO);
        return success(true);
    }
}
