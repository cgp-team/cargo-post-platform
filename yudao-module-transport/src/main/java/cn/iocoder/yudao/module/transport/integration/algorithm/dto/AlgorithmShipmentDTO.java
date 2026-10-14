package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 配对货运订单（揽收→派送完整链路）。
 * 对齐 Python PlanShipment：一个 shipment 展开为同一辆车上的 PICKUP + DELIVERY 两个节点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlgorithmShipmentDTO {

    /** 配对货运单号 */
    private String shipmentId;

    /** 揽收站点 */
    private String pickupStationId;

    /** 派送站点 */
    private String deliveryStationId;

    /** 件数 */
    private Integer quantity;

    /** 重量（kg）：车辆下发 cargoWeightCapacityKg 时算法按载重判可行性 */
    private Double weightKg;

    /** 体积（m³）：车辆下发 cargoVolumeCapacityM3 时算法按体积判可行性 */
    private Double volumeM3;

    /** 可选经济价值（向后兼容），业务层真实报价；缺失不报错。 */
    private Double economicValue;

    /**
     * 揽收时效下限：包裹最早可取时刻（业务侧 transport_order.earliest_pickup_time，
     * 没打包好之前车到了也取不到件）。算法会等到该时刻再揽收，等待计入班次时长。
     */
    private OffsetDateTime readyTime;

    /** 揽收截止时间（可选；业务侧尚无对应字段时不下发，算法不凭空判定迟到） */
    private OffsetDateTime pickupDeadline;

    /** 送达截止时间（业务侧 transport_order.latest_delivery_time） */
    private OffsetDateTime deliveryDeadline;

    /**
     * 优先级（越大越先获得稀缺舱位/时间余量）。业务侧尚无优先级字段时不下发，
     * 算法按订单号确定性排序，与旧行为一致。
     */
    private Integer priority;

    /**
     * 公交通行预检结论（与 AlgorithmOrderDTO.roadAccess 同语义）：
     * {@code BLOCKED} = 任一腿所在站点车辆进不去，整单改为最近骨架站交接
     * （配对单不能只挪一条腿，先取后送必须同车）。缺省 null = 不干预。
     */
    private String roadAccess;

}
