import { describe, expect, it } from 'vitest'
import {
  DISPATCH_MODE_LABELS,
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
  type TagType
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
  [SHIFT_STATUS_LABELS, SHIFT_STATUS_TAGS, '班次状态']
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
