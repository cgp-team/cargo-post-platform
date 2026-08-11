package cn.iocoder.yudao.module.transport.controller.app.transport.driver;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.controller.app.transport.driver.vo.*;
import cn.iocoder.yudao.module.transport.service.transport.driver.DriverAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
    @Operation(summary = "司机档案（按手机号识别身份）")
    @Parameter(name = "mobile", description = "登录会员手机号", required = true)
    public CommonResult<AppDriverProfileRespVO> profile(@RequestParam("mobile") String mobile) {
        return success(driverAppService.profile(mobile));
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
}
