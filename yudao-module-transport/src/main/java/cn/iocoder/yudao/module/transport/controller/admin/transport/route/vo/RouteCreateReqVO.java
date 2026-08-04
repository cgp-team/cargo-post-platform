package cn.iocoder.yudao.module.transport.controller.admin.transport.route.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Schema(description="Route Create")
@Data @EqualsAndHashCode(callSuper=true) @ToString(callSuper=true)
public class RouteCreateReqVO extends RouteBaseVO {}
