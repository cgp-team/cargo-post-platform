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

// ===== 车辆状态（VehicleDO.status 权威注释：0 空闲可用，1 停用维修）=====
// WEB-09: 修正——旧表误写「0空闲 1在途 2停用」，混入了 MonitoringVehicleRespVO.monitorStatus 的语义
export const VEHICLE_STATUS_LABELS: Record<number, string> = { 0: '可用', 1: '停用' }
export const VEHICLE_STATUS_TAGS: Record<number, TagType> = { 0: 'success', 1: 'danger' }

// ===== 车辆实时运营状态（VehicleDO.realtimeStatus）：0 空闲 1 在途 2 故障 3 离线 =====
export const VEHICLE_REALTIME_STATUS_LABELS: Record<number, string> = { 0: '空闲', 1: '在途', 2: '故障', 3: '离线' }
export const VEHICLE_REALTIME_STATUS_TAGS: Record<number, TagType> = { 0: 'success', 1: 'primary', 2: 'danger', 3: 'info' }

// ===== 监控车辆状态（MonitoringVehicleRespVO.status）：0 空闲 1 在途 2 停用 =====
// 与 VehicleDO.status（0 可用 / 1 停用）语义不同，监控页必须用这套映射
export const MONITOR_VEHICLE_STATUS_LABELS: Record<number, string> = { 0: '空闲', 1: '在途', 2: '停用' }
export const MONITOR_VEHICLE_STATUS_TAGS: Record<number, TagType> = { 0: 'success', 1: 'primary', 2: 'danger' }

// ===== 商品订单状态（ProductOrderStatusEnum）：0 待发货 1 已发货 2 已完成 3 已取消 =====
export const PRODUCT_ORDER_STATUS_LABELS: Record<number, string> = { 0: '待发货', 1: '已发货', 2: '已完成', 3: '已取消' }
export const PRODUCT_ORDER_STATUS_TAGS: Record<number, TagType> = { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }

// ===== 班次状态（ShiftExecutionDO.status）：0 未发车 1 在途 2 已完成 =====
export const SHIFT_STATUS_LABELS: Record<number, string> = { 0: '未发车', 1: '在途', 2: '已完成' }
export const SHIFT_STATUS_TAGS: Record<number, TagType> = { 0: 'primary', 1: 'success', 2: 'info' }

// ===== 交接状态（同源：后端 `TransportHandoverStatusEnum`，8 个值一个不许少）=====
// 这里以前写的是「待确认0 / 已确认1 / 有争议2」—— 三个都是自造码：
// 0 真名是"等待交接"、1 是"前序已到达"、2 是"接收方待接"，而后端把真名打在 statusName 那一列，
// 于是**同一列被两套语义读**：筛"有争议"拿到的是"接收方待接"的单，而 7=异常（标记争议真正落到的状态）
// 永远筛不到。列与筛选共用这一份映射，改枚举时两侧一起动。
export const HANDOVER_STATUS_LABELS: Record<number, string> = {
  0: '等待交接', 1: '前序已到达', 2: '接收方待接', 3: '交接中',
  4: '交接完成', 5: '交接超时', 6: '已取消', 7: '异常'
}
export const HANDOVER_STATUS_TAGS: Record<number, TagType> = {
  0: 'info', 1: 'primary', 2: 'warning', 3: 'warning',
  4: 'success', 5: 'danger', 6: 'info', 7: 'danger'
}
/** 唯一允许标记争议的状态判据：后端 `HandoverServiceImpl#disputeHandover` 只拦 COMPLETED(4)。
 *  前端多拦一个状态，就等于把一个后端肯接的动作藏掉；少拦一个，就是点下去必抛错。 */
export const HANDOVER_DISPUTE_BLOCKED_STATUS = 4

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
export const vehicleRealtimeStatusLabel = (status?: number | null) => labelOf(VEHICLE_REALTIME_STATUS_LABELS, status)
export const vehicleRealtimeStatusTag = (status?: number | null) => tagOf(VEHICLE_REALTIME_STATUS_TAGS, status)
export const monitorVehicleStatusLabel = (status?: number | null) => labelOf(MONITOR_VEHICLE_STATUS_LABELS, status)
export const monitorVehicleStatusTag = (status?: number | null) => tagOf(MONITOR_VEHICLE_STATUS_TAGS, status)
export const productOrderStatusLabel = (status?: number | null) => labelOf(PRODUCT_ORDER_STATUS_LABELS, status, '未知')
export const productOrderStatusTag = (status?: number | null) => tagOf(PRODUCT_ORDER_STATUS_TAGS, status)
export const shiftStatusLabel = (status?: number | null) => labelOf(SHIFT_STATUS_LABELS, status)
export const shiftStatusTag = (status?: number | null) => tagOf(SHIFT_STATUS_TAGS, status)
// 交接状态念不出来的时候必须露出码值本身：静默兜成"未知"会把"后端加了枚举、前端没跟"
// 这格藏成一片灰色，运营看见的是"未知"，调度查的是"为什么未知"
export const handoverStatusLabel = (status?: number | null) =>
  labelOf(HANDOVER_STATUS_LABELS, status, `后端新状态(${status})`)
export const handoverStatusTag = (status?: number | null) => tagOf(HANDOVER_STATUS_TAGS, status)

/**
 * 金额渲染只有一个出口：真零念 `¥0.00`，缺值念"没给"（队列 #68 / 台账 D-134）。
 * 起因是 `Number(value ?? 0).toFixed(2)` 这一形 —— 它把"后端没给这个键"念成"今天是 0 元"，
 * 而 0 是一个业务结论。实测契约要说清楚：DashboardController.sumOrderAmount 用的是
 * `IFNULL(SUM(total_amount), 0)`，buildDailyTrend 还会给空日期补 BigDecimal.ZERO ⇒
 * **今天**这些键恒发数字，所以这一改是防契约漂移，不是修一条当前错读。
 */
export const moneyText = (v?: number | string | null): string => {
  if (v === undefined || v === null || v === '') return '没给'
  const n = Number(v)
  return Number.isFinite(n) ? `¥${n.toFixed(2)}` : `没给（值不是数：${v}）`
}

/** 里程同理；单位交给调用方（有的表头里已经写了 (km)，不要在两处各念一遍） */
export const distanceText = (v?: number | string | null, unit = ''): string => {
  if (v === undefined || v === null || v === '') return '没给'
  const n = Number(v)
  if (!Number.isFinite(n)) return `没给（值不是数：${v}）`
  return unit ? `${n.toFixed(2)} ${unit}` : n.toFixed(2)
}
