package cn.iocoder.yudao.module.transport.service.order;

/**
 * 承运可行性审核（自动审核引擎）。
 *
 * 客户提交货运订单后必须先通过承运审核，结果合格才可进入订单池（READY_FOR_POOL）。
 * 审核维度（Phase 2 先覆盖危险品/禁运品/重量/尺寸/生鲜/服务模式，道路可达性、
 * 绕行成本、客运影响、时间窗口等随调度联动逐步扩展）：
 *  1. 危险品 / 禁运品（按货物名称关键词）→ REJECTED + DANGEROUS_GOODS / PROHIBITED_GOODS；
 *  2. 超重（重量上限可配）→ REJECTED + OVER_WEIGHT；
 *  3. 大件/超规（需客户送站或特殊安排）→ CONDITIONAL + CUSTOMER_ACTION_REQUIRED（送达转 CUSTOMER_TO_STATION）；
 *  4. 生鲜/需冷链 → MANUAL_REVIEW + MANUAL_REVIEW_REQUIRED（需人工确认承运条件）；
 *  5. 其余 → PASSED，两端站到站（STATION_TO_STATION）。
 *
 * 审核结果统一由 reasonCode（ReviewReasonCodeEnum）表达，前端负责映射文案，后端不硬编码散落文案。
 */
public interface CargoReviewService {

    /**
     * 对货运订单执行自动承运审核。
     *
     * @param goodsName       货物名称
     * @param weightKg        重量(kg)，可为 null
     * @param freshFlag       是否生鲜/需冷链
     * @param goodsNote       货物备注（大件/易碎等特征词）
     * @param pickupStationId 取货站点（本系统寄货为站到站，站点必选）
     * @param deliveryStationId 送达站点
     * @return 审核结果（reviewStatus + reasonCodes + 服务方式 + 建议站点）
     */
    CargoReviewResult review(String goodsName, java.math.BigDecimal weightKg, Boolean freshFlag,
                             String goodsNote, Long pickupStationId, Long deliveryStationId);

}
