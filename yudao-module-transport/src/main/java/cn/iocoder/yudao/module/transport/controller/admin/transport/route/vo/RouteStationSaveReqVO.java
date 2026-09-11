package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 保存线路经停站点序列（整线覆盖式保存：顺序即数组顺序）。
 *
 * <p>农村/园区没有现成公交路网时，管理员先「地图选点」建站，再用这里把站点按顺序拼成
 * 自己的客货邮线路，之后线路即参与附近公交、排班、调度、司机导航等全部功能。</p>
 */
@Schema(description = "管理后台 - 线路经停站点保存 Request VO")
@Data
public class RouteStationSaveReqVO {

    @Schema(description = "线路编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "线路编号不能为空")
    private Long routeId;

    @Schema(description = "按顺序排列的站点（可为空=清空站序）")
    @Valid
    private List<Item> stations;

    @Schema(description = "单个经停站点")
    @Data
    public static class Item {

        @Schema(description = "站点编号")
        @NotNull(message = "站点编号不能为空")
        private Long stationId;

        @Schema(description = "从起点累计计划分钟（可不填，后端按里程自动估算）")
        private Integer plannedMinutes;
    }
}
