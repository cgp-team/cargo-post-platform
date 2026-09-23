package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /** 重量（kg），算法当前不校验 */
    private Double weightKg;

    /** 体积（m³），算法当前不校验 */
    private Double volumeM3;

    /** 可选经济价值（向后兼容），业务层真实报价；缺失不报错。 */
    private Double economicValue;

}
