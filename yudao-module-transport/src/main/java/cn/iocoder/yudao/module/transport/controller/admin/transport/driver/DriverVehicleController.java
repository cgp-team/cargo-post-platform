package cn.iocoder.yudao.module.transport.controller.admin.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehicleBindReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehiclePageReqVO;
import cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo.DriverVehicleRespVO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.driver.DriverVehicleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.vehicle.VehicleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.driver.DriverMapper;
import cn.iocoder.yudao.module.transport.dal.mysql.vehicle.VehicleMapper;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverVehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 司机车辆绑定")
@RestController
@RequestMapping("/transport/driver-vehicle")
@Validated
public class DriverVehicleController {

    @Resource
    private DriverVehicleService driverVehicleService;
    @Resource
    private DriverMapper driverMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private cn.iocoder.yudao.module.transport.dal.mysql.route.RouteMapper routeMapper;

    @PostMapping("/bind")
    @Operation(summary = "绑定司机与车辆")
    @PreAuthorize("@ss.hasPermission('transport:driver:update')")
    public CommonResult<Boolean> bind(@Valid @RequestBody DriverVehicleBindReqVO reqVO) {
        driverVehicleService.bind(reqVO);
        return success(true);
    }

    @PutMapping("/unbind")
    @Operation(summary = "解绑司机与车辆")
    @Parameter(name = "id", description = "绑定记录编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:driver:update')")
    public CommonResult<Boolean> unbind(@RequestParam("id") Long id) {
        driverVehicleService.unbind(id);
        return success(true);
    }

    @GetMapping("/page")
    @Operation(summary = "获得人车绑定分页")
    @PreAuthorize("@ss.hasPermission('transport:driver:query')")
    public CommonResult<PageResult<DriverVehicleRespVO>> page(@Valid DriverVehiclePageReqVO pageReqVO) {
        PageResult<DriverVehicleDO> pageResult = driverVehicleService.getPage(pageReqVO);
        Map<Long, String> names = batchDriverNames(idsOf(pageResult.getList(), true));
        Map<Long, String> plates = batchPlateNos(idsOf(pageResult.getList(), false));
        List<DriverVehicleRespVO> list = pageResult.getList().stream()
                .map(dv -> fill(BeanUtils.toBean(dv, DriverVehicleRespVO.class), names, plates, routeNames(pageResult.getList())))
                .toList();
        return success(new PageResult<>(list, pageResult.getTotal()));
    }

    @GetMapping("/list-by-driver")
    @Operation(summary = "查询司机全部绑定记录")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    @PreAuthorize("@ss.hasPermission('transport:driver:query')")
    public CommonResult<List<DriverVehicleRespVO>> listByDriver(@RequestParam("driverId") Long driverId) {
        List<DriverVehicleDO> list = driverVehicleService.listByDriver(driverId);
        return success(list.stream()
                .map(vo -> fill(BeanUtils.toBean(vo, DriverVehicleRespVO.class),
                        Map.of(vo.getDriverId(), nameOf(vo.getDriverId())),
                        Map.of(vo.getVehicleId(), plateOf(vo.getVehicleId())),
                        routeNames(List.of(vo))))
                .toList());
    }

    // ========== 填充司机姓名 / 车牌号 ==========

    private static List<Long> idsOf(List<DriverVehicleDO> list, boolean driver) {
        return list.stream().map(dv -> driver ? dv.getDriverId() : dv.getVehicleId())
                .filter(Objects::nonNull).distinct().toList();
    }

    private Map<Long, String> batchDriverNames(List<Long> driverIds) {
        if (driverIds.isEmpty()) {
            return Map.of();
        }
        return driverMapper.selectBatchIds(driverIds).stream()
                .collect(Collectors.toMap(DriverDO::getId, DriverDO::getName, (a, b) -> a));
    }

    private Map<Long, String> batchPlateNos(List<Long> vehicleIds) {
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        return vehicleMapper.selectBatchIds(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleDO::getId, VehicleDO::getPlateNo, (a, b) -> a));
    }

    private String nameOf(Long driverId) {
        // 兜底 ""：Map.of 不允许 null 值
        DriverDO driver = driverId == null ? null : driverMapper.selectById(driverId);
        return driver == null ? "" : driver.getName();
    }

    private String plateOf(Long vehicleId) {
        // 兜底 ""：Map.of 不允许 null 值
        VehicleDO vehicle = vehicleId == null ? null : vehicleMapper.selectById(vehicleId);
        return vehicle == null ? "" : vehicle.getPlateNo();
    }

    private DriverVehicleRespVO fill(DriverVehicleRespVO vo, Map<Long, String> names, Map<Long, String> plates,
                                     Map<Long, String> routes) {
        vo.setDriverName(vo.getDriverId() == null ? null : names.getOrDefault(vo.getDriverId(), null));
        vo.setPlateNo(vo.getVehicleId() == null ? null : plates.getOrDefault(vo.getVehicleId(), null));
        // 车辆绑定的运营线路：运营范围校验/演示讲解都要看这一列（"这台车跑哪条线"）
        vo.setRouteName(vo.getRouteId() == null ? null : routes.get(vo.getRouteId()));
        return vo;
    }

    /** 绑定记录里出现的线路编号 → 线路名（一次查询，避免逐行回表） */
    private Map<Long, String> routeNames(List<DriverVehicleDO> list) {
        List<Long> routeIds = list.stream().map(DriverVehicleDO::getRouteId)
                .filter(Objects::nonNull).distinct().toList();
        if (routeIds.isEmpty()) {
            return Map.of();
        }
        return routeMapper.selectBatchIds(routeIds).stream()
                .collect(Collectors.toMap(cn.iocoder.yudao.module.transport.dal.dataobject.route.RouteDO::getId,
                        route -> route.getRouteName() == null ? "" : route.getRouteName(), (a, b) -> a));
    }

}
