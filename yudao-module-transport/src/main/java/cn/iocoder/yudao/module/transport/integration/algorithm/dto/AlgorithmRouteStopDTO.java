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
    public static final String ACTION_PASS = "PASS";
    public static final String ACTION_RETURN = "RETURN";

    private String stationId;

    /** 该站点作业的订单；场站起止点/骨架 PASS 无此字段 */
    private String orderId;

    /** DEPART / BOARD / ALIGHT / DELIVER / PICKUP / PASS / RETURN */
    private String action;

    /** 与上一站点间的分段里程 */
    private Double segmentDistance;

    /** 与上一站点间的分段路网行驶秒数（仅路网路径；为 null 时 ETA 按直线÷均速兜底） */
    private Long segmentDuration;

    /** 算法解释（仅货运/揽收经停携带）：是否被接受入方案 */
    private Boolean accepted;

    /** 服务方式（ServiceModeEnum.code） */
    private String serviceMode;

    /** 服务点（站点编号字符串） */
    private String servicePoint;

    /** 绕行距离(km)（相对骨架的额外行驶，骨架站为 0） */
    private Double detourDistance;

    /** 绕行时长(秒) */
    private Long detourDuration;

    /** 乘客影响(秒)：绕行对车上乘客的额外乘车时长（passenger-level，空车绕行为 null） */
    private Double passengerImpact;

    /** 未接受原因码（ReviewReasonCodeEnum.code；接受时为 null） */
    private String reasonCode;

}
