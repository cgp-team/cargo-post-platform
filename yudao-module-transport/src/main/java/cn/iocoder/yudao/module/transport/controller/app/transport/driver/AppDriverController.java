package cn.iocoder.yudao.module.transport.controller.app.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "用户 APP - 司机端")
@RestController
@RequestMapping("/transport/driver")
@Validated
public class AppDriverController {

    @Resource private DriverAppService driverAppService;

    @GetMapping("/profile")
    @Operation(summary = "司机档案（按登录会员识别身份）")
    public CommonResult<AppDriverProfileRespVO> profile() {
        return success(driverAppService.profile());
    }

    @GetMapping("/shifts")
    @Operation(summary = "今日班次与经停站点")
    public CommonResult<List<AppDriverShiftRespVO>> shifts() {
        return success(driverAppService.shifts());
    }

    @GetMapping("/pickups")
    @Operation(summary = "待装车任务")
    public CommonResult<List<AppDriverPickupRespVO>> pickups() {
        return success(driverAppService.pickups());
    }

    @GetMapping("/earnings")
    @Operation(summary = "运营统计")
    public CommonResult<AppDriverEarningsRespVO> earnings() {
        return success(driverAppService.earnings());
    }

    @GetMapping("/tasks")
    @Operation(summary = "调度任务（算法派单结果，预留）")
    @Parameter(name = "driverId", description = "司机编号", required = true)
    public CommonResult<List<AppDriverTaskRespVO>> tasks(@RequestParam("driverId") Long driverId) {
        return success(driverAppService.tasks(driverId));
    }

    @PostMapping("/depart")
    @Operation(summary = "发车（创建班次执行，货运订单推进已发车）")
    public CommonResult<Boolean> depart(@Valid @RequestBody AppDriverDepartReqVO reqVO) {
        driverAppService.depart(reqVO);
        return success(true);
    }

    @PostMapping("/arrive")
    @Operation(summary = "到站（更新当前站点，终点站到达完成班次）")
    public CommonResult<Boolean> arrive(@Valid @RequestBody AppDriverArriveReqVO reqVO) {
        driverAppService.arrive(reqVO);
        return success(true);
    }

    @PostMapping("/pickup-confirm")
    @Operation(summary = "确认装车（货运订单推进已发车）")
    public CommonResult<Boolean> pickupConfirm(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.pickupConfirm(reqVO);
        return success(true);
    }

    @PostMapping("/deliver")
    @Operation(summary = "确认送达（货运订单推进已完成）")
    public CommonResult<Boolean> deliver(@Valid @RequestBody AppDriverOrderActionReqVO reqVO) {
        driverAppService.deliver(reqVO);
        return success(true);
    }

    @PostMapping("/location")
    @Operation(summary = "上报车辆实时位置")
    public CommonResult<Boolean> reportLocation(@Valid @RequestBody AppDriverLocationReqVO reqVO) {
        driverAppService.reportLocation(reqVO);
        return success(true);
    }
}
