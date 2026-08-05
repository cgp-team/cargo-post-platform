package cn.iocoder.yudao.module.transport.controller.admin.transport.order.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 订单分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class TransportOrderPageReqVO extends PageParam {
    @Schema(description = "订单号")
    private String orderNo;
    @Schema(description = "订单类型")
    private Integer orderType;
    @Schema(description = "订单状态")
    private Integer status;
    @Schema(description = "取货站点编号")
    private Long pickupStationId;
    @Schema(description = "送达站点编号")
    private Long deliveryStationId;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    @Schema(description = "创建时间")
    private LocalDateTime[] createTime;
}
