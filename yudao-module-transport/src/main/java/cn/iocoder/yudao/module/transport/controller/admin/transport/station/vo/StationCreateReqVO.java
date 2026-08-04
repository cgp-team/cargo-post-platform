package cn.iocoder.yudao.module.transport.controller.admin.transport.station.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Schema(description="Station Create")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class StationCreateReqVO extends StationBaseVO {}
