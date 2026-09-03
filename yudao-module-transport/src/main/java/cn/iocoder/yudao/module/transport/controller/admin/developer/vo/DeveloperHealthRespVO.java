package cn.iocoder.yudao.module.transport.controller.admin.developer.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 系统健康检查 VO
 */
@Schema(description = "系统健康检查 Response VO")
@Data
public class DeveloperHealthRespVO {

    @Schema(description = "整体状态：HEALTHY/DEGRADED/OFFLINE")
    private String overallStatus;

    @Schema(description = "各服务状态")
    private List<ServiceHealth> services;

    @Schema(description = "服务健康状态")
    @Data
    public static class ServiceHealth {
        @Schema(description = "服务名称")
        private String name;
        @Schema(description = "状态：HEALTHY/DEGRADED/OFFLINE")
        private String status;
        @Schema(description = "延迟(ms)")
        private Long latencyMs;
        @Schema(description = "检查时间")
        private String checkedAt;
        @Schema(description = "错误信息")
        private String error;
    }
}
