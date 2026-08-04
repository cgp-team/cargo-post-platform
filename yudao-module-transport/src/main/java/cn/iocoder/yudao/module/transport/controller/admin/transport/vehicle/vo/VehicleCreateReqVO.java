package cn.iocoder.yudao.module.transport.controller.admin.transport.vehicle.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Schema(description="Vehicle Create")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class VehicleCreateReqVO extends VehicleBaseVO {}
