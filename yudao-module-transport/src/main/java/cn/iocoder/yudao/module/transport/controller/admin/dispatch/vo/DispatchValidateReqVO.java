package cn.iocoder.yudao.module.transport.controller.admin.dispatch.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 智能派单前约束校验 Request VO")
@Data
public class DispatchValidateReqVO {

    @Schema(description = "自动模式：true=一键智能调度校验（后端自动选场站/车辆）；false/不传=人工高级模式")
    private Boolean auto;

    @Schema(description = "场站编号（人工高级模式必填；auto=true 时忽略）", example = "1")
    private Long depotStationId;

    @Schema(description = "可用车辆编号列表（人工高级模式必填；auto=true 时忽略）")
    private List<Long> vehicleIds;

}
