package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 算法订单快照。容量只按件数约束；weightKg/volumeM3 为留存字段，算法不校验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmOrderDTO {

    /** 客运（上车站、下车站） */
    public static final String TYPE_PASSENGER = "PASSENGER";
    /** 派送（站点卸货） */
    public static final String TYPE_DELIVERY = "DELIVERY";
    /** 揽收（站点收货） */
    public static final String TYPE_PICKUP = "PICKUP";

    private String orderId;

    /** PASSENGER / DELIVERY / PICKUP */
    private String orderType;

    /** 客运订单上车站；强制先上车后下车 */
    private String boardingStationId;

    /** 客运订单下车站 */
    private String alightingStationId;

    /** 派送/揽收订单的作业站点 */
    private String stationId;

    /** 件数 */
    private Integer itemCount;

    /** 保留字段，算法不校验 */
    private Double weightKg;

    /** 保留字段，算法不校验 */
    private Double volumeM3;

    /** 货源类型：PRELOADED（场站预装）/ SHIPMENT（配对揽派，历史兼容）；省略时算法按上下文推断 */
    private String cargoSource;

}
