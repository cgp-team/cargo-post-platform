package cn.iocoder.yudao.module.transport.service.dispatch;

import cn.iocoder.yudao.module.transport.dal.dataobject.dispatch.PricingRuleDO;
import cn.iocoder.yudao.module.transport.dal.mysql.dispatch.PricingRuleMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 运输计价规则读取：单行配置，无记录或字段缺失时用默认值兜底（默认值与表结构 DEFAULT 一致）。
 */
@Service
public class PricingRuleService {

    /** 默认客运人公里单价(元) */
    static final BigDecimal DEFAULT_PASSENGER_PRICE_PER_KM = new BigDecimal("1.00");
    /** 默认货运件单价(元) */
    static final BigDecimal DEFAULT_CARGO_PRICE_PER_ITEM = new BigDecimal("5.00");
    /** 默认邮快件件单价(元) */
    static final BigDecimal DEFAULT_POSTAL_PRICE_PER_ITEM = new BigDecimal("3.00");
    /** 默认车辆公里成本(元) */
    static final BigDecimal DEFAULT_VEHICLE_COST_PER_KM = new BigDecimal("2.50");
    /** 默认平均时速(km/h) */
    static final double DEFAULT_AVG_SPEED_KMH = 25.0;
    /** 默认作业站停站分钟 */
    static final int DEFAULT_STOP_SERVICE_MINUTES = 3;

    @Resource private PricingRuleMapper pricingRuleMapper;

    /** 读取计价规则；无配置行时返回全默认值 */
    public PricingRuleDO getRule() {
        PricingRuleDO rule = pricingRuleMapper.selectFirst();
        return rule != null ? rule : defaultRule();
    }

    /** 全默认值规则（无配置行兜底） */
    public static PricingRuleDO defaultRule() {
        return PricingRuleDO.builder()
                .passengerPricePerKm(DEFAULT_PASSENGER_PRICE_PER_KM)
                .cargoPricePerItem(DEFAULT_CARGO_PRICE_PER_ITEM)
                .postalPricePerItem(DEFAULT_POSTAL_PRICE_PER_ITEM)
                .vehicleCostPerKm(DEFAULT_VEHICLE_COST_PER_KM)
                .avgSpeedKmh(BigDecimal.valueOf(DEFAULT_AVG_SPEED_KMH))
                .stopServiceMinutes(DEFAULT_STOP_SERVICE_MINUTES)
                .build();
    }
}
