import request from '@/config/axios'

/**
 * 运输计价规则（**单行配置**）。
 *
 * 后端 `PricingRuleService.getRule()` 在库里没有配置行时会返回**全默认值**，
 * 所以 `/get` 特意带 `configured` 旗标：false = 下面这些数只是兜底默认，库里还没配过。
 * 没有这个旗标，界面就会把 `1.00 元/人公里` 念成"运营配过的值"（本仓 D-090/#57 那一族"缺键当有值"）。
 */
export interface PricingRuleVO {
  id?: number
  passengerPricePerKm?: number
  cargoPricePerItem?: number
  postalPricePerItem?: number
  vehicleCostPerKm?: number
  avgSpeedKmh?: number
  stopServiceMinutes?: number
  configured?: boolean
}

/** 取当前生效的计价规则 */
export const getPricingRule = async () => {
  return await request.get({ url: '/transport/pricing-rule/get' })
}

/** 保存：有配置行则改，无则插入（`selectFirst` 按 id 升序取首条，所以只维护一行） */
export const savePricingRule = async (data: PricingRuleVO) => {
  return await request.put({ url: '/transport/pricing-rule/save', data })
}
