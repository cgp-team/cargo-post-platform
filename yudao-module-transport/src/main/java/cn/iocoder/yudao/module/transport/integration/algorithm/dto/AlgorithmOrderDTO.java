package cn.iocoder.yudao.module.transport.integration.algorithm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 算法订单快照。件数是基础容量口径；weightKg/volumeM3 在车辆下发对应运力时
 * 由算法按载重/体积复核（见 AlgorithmVehicleDTO.cargoWeightCapacityKg）。
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

    /** 重量（kg）：车辆下发载重运力后参与可行性判定 */
    private Double weightKg;

    /** 体积（m³）：车辆下发货仓体积运力后参与可行性判定 */
    private Double volumeM3;

    /** 货源类型：PRELOADED（场站预装）/ SHIPMENT（配对揽派，历史兼容）；省略时算法按上下文推断 */
    private String cargoSource;

    /**
     * 公交通行预检结论（站点档案 vehicle_access=false 时下发）：
     * <ul>
     *   <li>{@code BLOCKED} —— 公交车进不去该点位，算法不得安排绕行上门，改为最近骨架站交接；</li>
     *   <li>{@code NEAR_STATION} —— 点位距最近站点 ≤100 m，顺路在站点交接即可；</li>
     *   <li>null —— 未做预检或车辆可进入，行为与历史完全一致。</li>
     * </ul>
     * 只标注货运作业点，客运订单永不标注（每站必停拉客，乘客点位不由算法改派）。
     */
    private String roadAccess;

    /**
     * 可选经济价值（向后兼容）：由业务层用 CargoPricingService 真实报价填充。
     * 算法侧缺失时不报错、不造假价格；仅用于性价比比较，不突破硬约束。
     */
    private Double economicValue;

    /**
     * 逐单时效（与 AlgorithmShipmentDTO 同语义、同源字段）：以前这四列只有配对单有，
     * 「站点货运单」（村↔场站，生产主流量）等于没有任何承诺时刻下发给算法 ——
     * 迟到方案照样报可行。填的是 transport_order 的真实值
     * （earliest_pickup_time / latest_delivery_time），没填即 null、算法不凭空判。
     * 算法侧按任务类型分档接入（app/haco/v14_solver.py 的 _encode_tasks）：
     * 派送单的 deliveryDeadline 有 DELIVER 事件锚点会判；揽收单的 deliveryDeadline
     * 目前没有事件锚点（货随车回场站才到位），下发是为了让请求自证事实，
     * 判它要等实际到时刻回流那一轮。
     */
    private OffsetDateTime readyTime;

    /** 揽收截止时间（业务侧尚无对应字段时不下发，算法不凭空判定迟到） */
    private OffsetDateTime pickupDeadline;

    /** 送达截止时间 */
    private OffsetDateTime deliveryDeadline;

    /** 优先级（越大越先获得稀缺舱位/时间余量）；业务侧尚无优先级字段时不下发 */
    private Integer priority;

}
