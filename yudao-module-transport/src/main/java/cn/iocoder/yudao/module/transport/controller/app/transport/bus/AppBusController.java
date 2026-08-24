package cn.iocoder.yudao.module.transport.controller.app.transport.bus;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusLineRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusNearbyRespVO;
import cn.iocoder.yudao.module.transport.controller.app.transport.bus.vo.AppBusRespVO;
import cn.iocoder.yudao.module.transport.service.transport.bus.AppBusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 实时公交")
@RestController
@RequestMapping("/transport/bus")
@Validated
public class AppBusController {

    @Resource private AppBusService appBusService;

    @GetMapping("/realtime")
    @PermitAll
    @Operation(summary = "实时公交列表（含线路起终点/下一站/ETA/位置）")
    public CommonResult<List<AppBusRespVO>> realtime() {
        return success(appBusService.getRealtimeBuses());
    }

    @GetMapping("/lines")
    @PermitAll
    @Operation(summary = "实时公交线路（含经停点与该线在线车辆，供车来了式地图+列表）")
    public CommonResult<List<AppBusLineRespVO>> lines() {
        return success(appBusService.getLines());
    }

    @GetMapping("/nearby")
    @PermitAll
    @Operation(summary = "附近实时公交（按用户坐标 Haversine 过滤 radius 内站点/车辆；无坐标时按区域名 fallback）")
    public CommonResult<AppBusNearbyRespVO> nearby(
            @RequestParam(value = "latitude", required = false) Double latitude,
            @RequestParam(value = "longitude", required = false) Double longitude,
            @RequestParam(value = "radius", required = false) Double radius,
            @RequestParam(value = "district", required = false) String district) {
        return success(appBusService.getNearbyBuses(latitude, longitude, radius, district));
    }

}
