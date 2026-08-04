package cn.iocoder.yudao.module.transport.controller.admin.transport.driver.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Schema(description="Driver Create")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class DriverCreateReqVO extends DriverBaseVO {}
