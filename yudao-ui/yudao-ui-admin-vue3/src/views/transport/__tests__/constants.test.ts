import { describe, expect, it } from 'vitest'
import {
  DISPATCH_MODE_LABELS,
  HANDOVER_DISPUTE_BLOCKED_STATUS,
  HANDOVER_STATUS_LABELS,
  HANDOVER_STATUS_TAGS,
  ORDER_STATUS_LABELS,
  ORDER_STATUS_TAGS,
  ORDER_TYPE_LABELS,
  ORDER_TYPE_TAGS,
  PLAN_STATUS_LABELS,
  PLAN_STATUS_TAGS,
  PRODUCT_ORDER_STATUS_LABELS,
  PRODUCT_ORDER_STATUS_TAGS,
  REVIEW_STATUS_LABELS,
  SERVICE_MODE_LABELS,
  SHIFT_STATUS_LABELS,
  SHIFT_STATUS_TAGS,
  STOP_ACTION_LABELS,
  STOP_ACTION_TAGS,
  VEHICLE_REALTIME_STATUS_LABELS,
  VEHICLE_REALTIME_STATUS_TAGS,
  VEHICLE_STATUS_LABELS,
  VEHICLE_STATUS_TAGS,
  dispatchModeLabel,
  handoverStatusLabel,
  handoverStatusTag,
  labelOf,
  orderStatusLabel,
  planStatusLabel,
  productOrderStatusLabel,
  reviewStatusLabel,
  serviceModeLabel,
  shiftStatusLabel,
  stopActionLabel,
  tagOf,
  vehicleRealtimeStatusLabel,
  vehicleStatusLabel,
  type TagType,
  moneyText,
  distanceText,
  eventPhotoState

} from '../constants'

/**
 * ENG-04 契约测试：锁定 transport 模块枚举单一来源。
 *
 * 背景：订单状态映射此前散落多页各自定义，已出现口径漂移
 * （dispatch-center 缺 6/7，order/dispatch 缺 9-13；WEB-09 车辆状态两字段语义混写）。
 * 本测试防止回归：任何枚举全集变化必须显式修改此处，而不是静默缺项。
 */

/** 数字键枚举表：LABELS 与 TAGS 的键集合必须一致（防标签颜色映射缺项） */
const PAIRED_TABLES: Array<[Record<number, string>, Record<number, TagType>, string]> = [
  [ORDER_TYPE_LABELS, ORDER_TYPE_TAGS, '订单类型'],
  [ORDER_STATUS_LABELS, ORDER_STATUS_TAGS, '订单状态'],
  [PLAN_STATUS_LABELS, PLAN_STATUS_TAGS, '方案状态'],
  [STOP_ACTION_LABELS, STOP_ACTION_TAGS, '经停动作'],
  [VEHICLE_STATUS_LABELS, VEHICLE_STATUS_TAGS, '车辆状态'],
  [VEHICLE_REALTIME_STATUS_LABELS, VEHICLE_REALTIME_STATUS_TAGS, '车辆实时状态'],
  [PRODUCT_ORDER_STATUS_LABELS, PRODUCT_ORDER_STATUS_TAGS, '商品订单状态'],
  [SHIFT_STATUS_LABELS, SHIFT_STATUS_TAGS, '班次状态'],
  [HANDOVER_STATUS_LABELS, HANDOVER_STATUS_TAGS, '交接状态']
]

describe('transport 枚举契约（单一来源）', () => {
  it('订单状态覆盖后端 TransportOrderStatusEnum 0-13 全集', () => {
    // dispatch-center 曾缺 6/7、order/dispatch 曾缺 9-13——全集锁定防止再漂移
    const keys = Object.keys(ORDER_STATUS_LABELS).map(Number).sort((a, b) => a - b)
    expect(keys).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13])
  })

  it('每组 LABELS 与 TAGS 键集合一致（标签颜色映射不缺项）', () => {
    for (const [labels, tags, name] of PAIRED_TABLES) {
      const labelKeys = Object.keys(labels).sort()
      const tagKeys = Object.keys(tags).sort()
      expect(tagKeys, `${name} 的 TAGS 键与 LABELS 不一致`).toEqual(labelKeys)
    }
  })

  it('WEB-09 回归：车辆静态状态与实时运营状态是两个语义不同的字段', () => {
    // VehicleDO.status（0 可用 / 1 停用）≠ realtimeStatus（0 空闲 / 1 在途 / 2 故障 / 3 离线）
    expect(Object.keys(VEHICLE_STATUS_LABELS).sort()).toEqual(['0', '1'])
    expect(VEHICLE_STATUS_LABELS[0]).toBe('可用')
    expect(VEHICLE_STATUS_LABELS[1]).toBe('停用')
    expect(Object.keys(VEHICLE_REALTIME_STATUS_LABELS).sort()).toEqual(['0', '1', '2', '3'])
    expect(VEHICLE_REALTIME_STATUS_LABELS).toEqual({ 0: '空闲', 1: '在途', 2: '故障', 3: '离线' })
  })

  it('商品订单状态与 ProductOrderStatusEnum 一致（0 待发货 1 已发货 2 已完成 3 已取消）', () => {
    expect(PRODUCT_ORDER_STATUS_LABELS).toEqual({
      0: '待发货', 1: '已发货', 2: '已完成', 3: '已取消'
    })
  })

  it('派单方式：0 手工 / 1 智能', () => {
    expect(DISPATCH_MODE_LABELS).toEqual({ 0: '手工', 1: '智能' })
  })

  it('服务方式五个枚举与后端 ServiceModeEnum 对齐', () => {
    expect(Object.keys(SERVICE_MODE_LABELS).sort()).toEqual(
      ['CUSTOMER_TO_STATION', 'DOOR_PICKUP', 'NEAREST_STATION', 'SAFE_ROADSIDE', 'STATION_TO_STATION'].sort()
    )
  })

  it('承运审核结果覆盖 0-4', () => {
    expect(Object.keys(REVIEW_STATUS_LABELS).map(Number).sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4])
  })

  it('WEB-1 回归：交接状态与后端 TransportHandoverStatusEnum 的 0-7 全名逐字一致', () => {
    // 页面上那三个自造码（待确认0/已确认1/有争议2）曾让筛"有争议"拿到的是"接收方待接"的单，
    // 而 7=异常（标记争议真正落到的状态）永远筛不到 —— 全名锁定，改枚举必须同时改这里
    expect(HANDOVER_STATUS_LABELS).toEqual({
      0: '等待交接',
      1: '前序已到达',
      2: '接收方待接',
      3: '交接中',
      4: '交接完成',
      5: '交接超时',
      6: '已取消',
      7: '异常'
    })
  })

  it('WEB-1 回归：标记争议只对后端肯接的状态露出（后端只拦 COMPLETED=4）', () => {
    expect(HANDOVER_DISPUTE_BLOCKED_STATUS).toBe(4)
    // 这一格是"界面别承诺做不到的动作"与"别藏掉后端肯接的动作"两条线的交点：
    // 4 必须能被筛到，否则按钮的隐藏条件与枚举脱钩
    expect(HANDOVER_STATUS_LABELS[HANDOVER_DISPUTE_BLOCKED_STATUS]).toBe('交接完成')
    expect(HANDOVER_STATUS_LABELS[2]).toBe('接收方待接')
    expect(HANDOVER_STATUS_LABELS[7]).toBe('异常')
  })

  it('交接状态念不出来时必须露出码值，不许静默兜成"未知"', () => {
    // 后端加了枚举而前端没跟，症状本来是隐形的（一片"未知"）；这里要求把码值念出来
    expect(handoverStatusLabel(8)).toContain('8')
    expect(handoverStatusLabel(undefined)).toBe('-')
    expect(handoverStatusTag(4)).toBe('success')
    expect(handoverStatusTag(7)).toBe('danger')
  })
})

describe('labelOf / tagOf 兜底行为', () => {
  it('labelOf：null / undefined 返回 "-"（而非 "未知"）', () => {
    expect(labelOf(ORDER_STATUS_LABELS, null)).toBe('-')
    expect(labelOf(ORDER_STATUS_LABELS, undefined)).toBe('-')
  })

  it('labelOf：已知值返回映射文案，未知数值返回 fallback', () => {
    expect(labelOf(ORDER_STATUS_LABELS, 4)).toBe('已完成')
    expect(labelOf(ORDER_STATUS_LABELS, 99)).toBe('未知')
  })

  it('tagOf：空值与未知值统一回退 info 标签', () => {
    expect(tagOf(ORDER_STATUS_TAGS, null)).toBe('info')
    expect(tagOf(ORDER_STATUS_TAGS, undefined)).toBe('info')
    expect(tagOf(ORDER_STATUS_TAGS, 99)).toBe('info')
  })
})

describe('具名取值函数', () => {
  it('orderStatusLabel 未知状态返回带原值的提示（便于排障）', () => {
    expect(orderStatusLabel(2)).toBe('已分配')
    expect(orderStatusLabel(42)).toBe('状态42')
    expect(orderStatusLabel(null)).toBe('-')
  })

  it('planStatusLabel / stopActionLabel / shiftStatusLabel 正常取值', () => {
    expect(planStatusLabel(2)).toBe('执行中')
    expect(stopActionLabel(3)).toBe('派送')
    expect(shiftStatusLabel(1)).toBe('在途')
  })

  it('vehicleStatusLabel / vehicleRealtimeStatusLabel 走各自独立映射', () => {
    expect(vehicleStatusLabel(0)).toBe('可用')
    expect(vehicleRealtimeStatusLabel(0)).toBe('空闲')
    // 关键区分：同一个 0，两个字段语义不同
    expect(vehicleStatusLabel(0)).not.toBe(vehicleRealtimeStatusLabel(0))
  })

  it('productOrderStatusLabel 未知状态返回"未知"', () => {
    expect(productOrderStatusLabel(0)).toBe('待发货')
    expect(productOrderStatusLabel(9)).toBe('未知')
  })

  it('reviewStatusLabel 未知值返回 "-"（无 fallback 参数的独立实现）', () => {
    expect(reviewStatusLabel(1)).toBe('已通过')
    expect(reviewStatusLabel(42)).toBe('-')
    expect(reviewStatusLabel(undefined)).toBe('-')
  })

  it('serviceModeLabel：未知名回退原值，空值返回 "-"', () => {
    expect(serviceModeLabel('STATION_TO_STATION')).toBe('站到站')
    expect(serviceModeLabel('UNKNOWN_MODE')).toBe('UNKNOWN_MODE')
    expect(serviceModeLabel('')).toBe('-')
  })

  it('dispatchModeLabel 空值兜底', () => {
    expect(dispatchModeLabel(1)).toBe('智能')
    expect(dispatchModeLabel(null)).toBe('-')
  })
})

// ── 金额/里程缺值渲染（队列 #68 / 台账 D-134）：真零与"没给"必须是两格 ──
describe('moneyText / distanceText（缺值不折叠成 0）', () => {
  it('真零照念 ¥0.00 —— 0 是业务结论，不能被吞进"没给"那一格', () => {
    expect(moneyText(0)).toBe('¥0.00')
    expect(distanceText(0)).toBe('0.00')
  })
  it('null / undefined / 空串都念"没给"，不替后端说"今天是 0 元"', () => {
    expect(moneyText(null)).toBe('没给')
    expect(moneyText(undefined)).toBe('没给')
    expect(moneyText('')).toBe('没给')
    expect(distanceText(null)).toBe('没给')
  })
  it('字符串数额同样格式化（后端 BigDecimal 序列化可能是串）', () => {
    expect(moneyText('12.5')).toBe('¥12.50')
    expect(distanceText('7.125')).toBe('7.13')
  })
  it('不是数的值点名，不静默变 NaN 或 0.00', () => {
    expect(moneyText('abc')).toBe('没给（值不是数：abc）')
    expect(distanceText('abc')).toBe('没给（值不是数：abc）')
  })
  it('单位只在调用方念一次（表头已写 km 的格子不再追加）', () => {
    expect(distanceText(3.5, 'km')).toBe('3.50 km')
    expect(distanceText(3.5)).toBe('3.50')
  })
})

// ── 事件现场照片的三态（队列 #60 / 台账 D-135）：没有 / 读不出 / 有地址 ──
describe('eventPhotoState（extraData 里的现场照片）', () => {
  it('没有 extraData 就是"没有附件"，不报"读不出"', () => {
    expect(eventPhotoState(undefined)).toEqual({ url: '', unreadable: false })
    expect(eventPhotoState('')).toEqual({ url: '', unreadable: false })
  })
  it('合法 JSON 但没有 photoUrl 键：算"没有附件"，不算读不出（别的事件也往这一格写别的键）', () => {
    expect(eventPhotoState('{"foo":1}')).toEqual({ url: '', unreadable: false })
  })
  it('photoUrl 是个串就念出来（后台要点开看）', () => {
    expect(eventPhotoState('{"photoUrl":"https://x/a.jpg"}').url).toBe('https://x/a.jpg')
  })
  it('JSON 坏了必须明说"读不出"，不能塌成"这单没拍照"', () => {
    expect(eventPhotoState('{"photoUrl":')).toEqual({ url: '', unreadable: true })
  })
})
