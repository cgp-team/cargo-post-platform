/**
 * 运输模块共享枚举常量（单一来源）。
 *
 * 背景（2026-09 审计）：订单类型/状态映射此前散落在 order、dispatch、dispatch-center 等
 * 页面各自定义，已出现口径漂移——dispatch-center 缺「6 待审核 / 7 待客户操作」显示为"—"，
 * order/dispatch 缺多段联运状态 9-13。本文件按后端权威枚举统一：
 *   - 订单状态：TransportOrderStatusEnum（yudao-module-transport enums/dispatch）
 *   - 其余：对应后端 DO 字段注释 / 枚举；改动前先核对后端定义
 *
 * 说明：未采用 DictTag——它依赖 system_dict_data 表的字典数据，需另配 SQL 种子；
 * 先以 TS 常量收敛口径，后续若接字典再平滑替换。
 */

export type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

// ===== 订单类型（TransportOrderDO.orderType）=====
export const ORDER_TYPE_LABELS: Record<number, string> = { 1: '客运', 2: '货运', 3: '邮快件' }
export const ORDER_TYPE_TAGS: Record<number, TagType> = { 1: 'success', 2: 'warning', 3: 'info' }

// ===== 订单状态（TransportOrderStatusEnum，0-13 全集）=====
export const ORDER_STATUS_LABELS: Record<number, string> = {
  0: '已创建', 1: '已入池', 2: '已分配', 3: '已发车', 4: '已完成', 5: '已取消',
  6: '待审核', 7: '待客户操作', 8: '待入池',
  9: '部分完成', 10: '运输中', 11: '换乘中', 12: '派送中', 13: '异常'
}
export const ORDER_STATUS_TAGS: Record<number, TagType> = {
  0: 'info', 1: 'warning', 2: 'primary', 3: 'success', 4: 'success', 5: 'danger',
  6: 'warning', 7: 'warning', 8: 'warning',
  9: 'primary', 10: 'primary', 11: 'warning', 12: 'primary', 13: 'danger'
}

// ===== 方案状态（DispatchPlanDO.status）：0 待审核 1 已下发 2 执行中 3 已完成 4 已作废 =====
export const PLAN_STATUS_LABELS: Record<number, string> = {
  0: '待审核', 1: '已下发', 2: '执行中', 3: '已完成', 4: '已作废'
}
export const PLAN_STATUS_TAGS: Record<number, TagType> = {
  0: 'warning', 1: 'primary', 2: 'success', 3: 'info', 4: 'danger'
}

// ===== 派单方式（DispatchPlanDO.planningMode 之外的任务口径）：0 手工 1 智能 =====
export const DISPATCH_MODE_LABELS: Record<number, string> = { 0: '手工', 1: '智能' }
export const DISPATCH_MODE_TAGS: Record<number, TagType> = { 0: 'info', 1: 'success' }

// ===== 经停动作（DispatchPlanItemDO.action）：0 出发 1 接客 2 送客 3 派送 4 揽收 5 返回 6 经停 =====
export const STOP_ACTION_LABELS: Record<number, string> = {
  0: '出发', 1: '接客', 2: '送客', 3: '派送', 4: '揽收', 5: '返回', 6: '经停'
}
export const STOP_ACTION_TAGS: Record<number, TagType> = {
  0: 'info', 1: 'success', 2: 'warning', 3: 'primary', 4: 'primary', 5: 'info', 6: 'info'
}

// ===== 寄货服务方式（ServiceModeEnum）=====
export const SERVICE_MODE_LABELS: Record<string, string> = {
  DOOR_PICKUP: '上门交接',
  NEAREST_STATION: '最近站点交接',
  SAFE_ROADSIDE: '安全点交接',
  CUSTOMER_TO_STATION: '客户送站',
  STATION_TO_STATION: '站到站'
}

// ===== 承运审核结果：0 待审核 1 通过 2 需客户操作 3 需人工审核 4 不承运 =====
export const REVIEW_STATUS_LABELS: Record<number, string> = {
  0: '待审核', 1: '已通过', 2: '需客户操作', 3: '需人工审核', 4: '不承运'
}

// ===== 车辆状态（VehicleDO.status）：0 空闲 1 在途 2 停用 =====
export const VEHICLE_STATUS_LABELS: Record<number, string> = { 0: '空闲', 1: '在途', 2: '停用' }
export const VEHICLE_STATUS_TAGS: Record<number, TagType> = { 0: 'info', 1: 'success', 2: 'danger' }

// ===== 班次状态（ShiftExecutionDO.status）：0 未发车 1 在途 2 已完成 =====
export const SHIFT_STATUS_LABELS: Record<number, string> = { 0: '未发车', 1: '在途', 2: '已完成' }
export const SHIFT_STATUS_TAGS: Record<number, TagType> = { 0: 'primary', 1: 'success', 2: 'info' }

// ===== 取值辅助（统一 undefined/null 兜底文案）=====
export const labelOf = (map: Record<number, string>, value?: number | null, fallback = '未知') =>
  value === undefined || value === null ? '-' : map[value] ?? fallback
export const tagOf = (map: Record<number, TagType>, value?: number | null): TagType =>
  value === undefined || value === null ? 'info' : map[value] ?? 'info'

export const orderTypeLabel = (type?: number | null) => labelOf(ORDER_TYPE_LABELS, type)
export const orderTypeTag = (type?: number | null) => tagOf(ORDER_TYPE_TAGS, type)
export const orderStatusLabel = (status?: number | null) => labelOf(ORDER_STATUS_LABELS, status, `状态${status}`)
export const orderStatusTag = (status?: number | null) => tagOf(ORDER_STATUS_TAGS, status)
export const planStatusLabel = (status?: number | null) => labelOf(PLAN_STATUS_LABELS, status)
export const planStatusTag = (status?: number | null) => tagOf(PLAN_STATUS_TAGS, status)
export const dispatchModeLabel = (mode?: number | null) => labelOf(DISPATCH_MODE_LABELS, mode)
export const dispatchModeTag = (mode?: number | null) => tagOf(DISPATCH_MODE_TAGS, mode)
export const stopActionLabel = (action?: number | null) => labelOf(STOP_ACTION_LABELS, action)
export const stopActionTag = (action?: number | null) => tagOf(STOP_ACTION_TAGS, action)
export const serviceModeLabel = (code?: string) => (code ? SERVICE_MODE_LABELS[code] || code : '-')
export const serviceModeTag = (code?: string): TagType =>
  code === 'DOOR_PICKUP' ? 'success' : code ? 'warning' : 'info'
export const reviewStatusLabel = (status?: number | null) =>
  status === undefined || status === null ? '-' : REVIEW_STATUS_LABELS[status] ?? '-'
export const vehicleStatusLabel = (status?: number | null) => labelOf(VEHICLE_STATUS_LABELS, status)
export const vehicleStatusTag = (status?: number | null) => tagOf(VEHICLE_STATUS_TAGS, status)
export const shiftStatusLabel = (status?: number | null) => labelOf(SHIFT_STATUS_LABELS, status)
export const shiftStatusTag = (status?: number | null) => tagOf(SHIFT_STATUS_TAGS, status)
