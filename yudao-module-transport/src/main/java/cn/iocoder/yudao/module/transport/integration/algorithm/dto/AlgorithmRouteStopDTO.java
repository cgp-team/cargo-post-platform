package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 闭环站点访问序列中的一个经停点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmRouteStopDTO {

    public static final String ACTION_DEPART = "DEPART";
    public static final String ACTION_BOARD = "BOARD";
    public static final String ACTION_ALIGHT = "ALIGHT";
    public static final String ACTION_DELIVER = "DELIVER";
    public static final String ACTION_PICKUP = "PICKUP";
    public static final String ACTION_RETURN = "RETURN";

    private String stationId;

    /** 该站点作业的订单；场站起止点无此字段 */
    private String orderId;

    /** DEPART / BOARD / ALIGHT / DELIVER / PICKUP / RETURN */
    private String action;

    /** 与上一站点间的分段里程 */
    private Double segmentDistance;

}
