package cn.iocoder.yudao.module.transport.controller.admin.bigscreen;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.transport.service.bigscreen.BigScreenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 智慧大屏专用接口
 *
 * SEP-01：一次聚合大屏 T2 层全部模块（摘要 KPI + 今日班次 + 返程结算 + 订单趋势 + 分布），
 * 服务端 Redis 缓存 48s（= 60s 刷新间隔 × 0.8），N 个观看者等价于 1 个用户的 DB 压力。
 * 权限复用 transport:dashboard:query，避免新增菜单权限的 DB 种子变更。
 */
@Tag(name = "管理后台 - 智慧大屏")
@RestController
@RequestMapping("/transport/bigscreen")
public class BigScreenController {

    @Resource private BigScreenService bigScreenService;

    @GetMapping("/overview")
    @Operation(summary = "大屏聚合数据（缓存 48s）")
    @PreAuthorize("@ss.hasPermission('transport:dashboard:query')")
    public CommonResult<Map<String, Object>> overview() {
        return success(bigScreenService.getOverview());
    }

}
