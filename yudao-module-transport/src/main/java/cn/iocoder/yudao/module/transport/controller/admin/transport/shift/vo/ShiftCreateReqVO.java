package cn.iocoder.yudao.module.transport.controller.admin.transport.shift.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 班次创建 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class ShiftCreateReqVO extends ShiftBaseVO {
}
