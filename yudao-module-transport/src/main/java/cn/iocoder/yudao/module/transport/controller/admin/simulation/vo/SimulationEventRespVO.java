package cn.iocoder.yudao.module.transport.controller.admin.simulation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模拟事件 VO
 */
@Schema(description = "模拟事件 Response VO")
@Data
public class SimulationEventRespVO {

    @Schema(description = "事件ID")
    private Long id;
    @Schema(description = "模拟运行ID")
    private Long runId;
    @Schema(description = "事件类型")
    private String eventType;
    @Schema(description = "严重级别：0信息 1警告 2严重")
    private Integer severity;
    @Schema(description = "事件标题")
    private String title;
    @Schema(description = "事件内容")
    private String content;
    @Schema(description = "模拟时刻(秒)")
    private Long simSeconds;
    @Schema(description = "相关站点名")
    private String stationName;
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
