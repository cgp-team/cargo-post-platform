package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.PricingRuleDO;
import cn.iocoder.yudao.module.transport.dal.dataobject.station.StationDO;
import cn.iocoder.yudao.module.transport.dal.mysql.station.StationMapper;
import cn.iocoder.yudao.module.transport.util.GeoDistanceUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 寄货计价（货运口径）：件单价 × 件数 + 里程费，与 {@link DispatchEstimationService} 的
 * 方案预计收入同源（同一路网系数、同一计价规则表）。
 *
 * <p>为什么独立成服务：村民在寄货页"填完取货/送达地址"就应该看到金额，
 * 而金额此前只在方案估算里算过、订单主表 total_amount 从没落库，
 * 后台订单管理因此一直显示空金额（列表列已存在，只是没数据）。</p>
 */
@Service
public class CargoPricingService {

    @Resource private StationMapper stationMapper;
    @Resource private PricingRuleService pricingRuleService;

    /** 计价结果：金额 + 明细（小程序展示"件单价 / 里程费"用） */
    public record CargoQuote(
            BigDecimal amount,
            BigDecimal itemFee,
            BigDecimal distanceFee,
            BigDecimal distanceKm,
            Integer itemCount,
            BigDecimal pricePerItem,
            BigDecimal pricePerKm) {
    }

    /**
     * 货运试算：取/送站坐标缺失时只计件单价（不臆造里程费）。
     *
     * @param pickupStationId  取货站点编号
     * @param deliveryStationId 送达站点编号
     * @param itemCount        件数（null/<=0 按 1 件）
     */
    public CargoQuote quote(Long pickupStationId, Long deliveryStationId, Integer itemCount) {
        int count = itemCount != null && itemCount > 0 ? itemCount : 1;
        PricingRuleDO rule = pricingRuleService.getRule();
        BigDecimal pricePerItem = rule.getCargoPricePerItem() != null
                ? rule.getCargoPricePerItem() : PricingRuleService.DEFAULT_CARGO_PRICE_PER_ITEM;
        BigDecimal pricePerKm = rule.getPassengerPricePerKm() != null
                ? rule.getPassengerPricePerKm() : PricingRuleService.DEFAULT_PASSENGER_PRICE_PER_KM;

        BigDecimal itemFee = pricePerItem.multiply(BigDecimal.valueOf(count));

        BigDecimal distanceKm = null;
        BigDecimal distanceFee = BigDecimal.ZERO;
        StationDO from = pickupStationId == null ? null : stationMapper.selectById(pickupStationId);
        StationDO to = deliveryStationId == null ? null : stationMapper.selectById(deliveryStationId);
        if (hasCoords(from) && hasCoords(to)) {
            double straightKm = GeoDistanceUtil.haversineKm(
                    from.getLongitude().doubleValue(), from.getLatitude().doubleValue(),
                    to.getLongitude().doubleValue(), to.getLatitude().doubleValue())
                    * DispatchEstimationService.ROAD_FACTOR_FOR_PRICE;
            distanceKm = BigDecimal.valueOf(straightKm).setScale(2, RoundingMode.HALF_UP);
            distanceFee = pricePerKm.multiply(BigDecimal.valueOf(straightKm));
        }

        return new CargoQuote(
                itemFee.add(distanceFee).setScale(2, RoundingMode.HALF_UP),
                itemFee.setScale(2, RoundingMode.HALF_UP),
                distanceFee.setScale(2, RoundingMode.HALF_UP),
                distanceKm,
                count,
                pricePerItem,
                pricePerKm);
    }

    private static boolean hasCoords(StationDO station) {
        return station != null && station.getLongitude() != null && station.getLatitude() != null;
    }

}
