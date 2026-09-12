import request from '@/config/axios'

/** 调度订单池订单(对应 TransportOrderDO) */
export interface DispatchOrderVO {
  id?: number
  orderNo?: string
  orderType?: number
  pickupStationId?: number
  deliveryStationId?: number
  earliestPickupTime?: string
  latestDeliveryTime?: string
  status?: number
  totalAmount?: number
  createTime?: string
}

/** 调度方案(对应 DispatchPlanDO) */
export interface DispatchPlanVO {
  id?: number
  taskId?: number
  planVersion?: number
  mode?: number
  algorithmVersion?: string
  parameterVersion?: string
  score?: number
  totalDistance?: number
  estDurationMinutes?: number
  estRevenue?: number
  estCost?: number
  status?: number
  approvedBy?: number
  approvedTime?: string
  createTime?: string
}

/** 调度方案明细(对应 DispatchPlanItemDO) */
export interface DispatchPlanItemVO {
  id?: number
  planId?: number
  vehicleId?: number
  driverId?: number
  shiftId?: number
  orderId?: number
  stationId?: number
  visitSequence?: number
  actionType?: number
  estimatedArrivalTime?: string
  /** 展示用（后端 getPlan 补齐）：站点名 */
  stationName?: string
  /** 展示用（后端 getPlan 补齐）：订单号 */
  orderNo?: string
  /** 计划离站时间 */
  plannedDepartureTime?: string
  /** 本站作业时长(秒) */
  serviceDurationSeconds?: number
  /** 分段里程(km) */
  segmentDistanceKm?: number
  /** 数量（BOARD/ALIGHT=人数，PICKUP/DELIVER=件数） */
  quantity?: number
  /** 服务方式（ServiceModeEnum.code，仅货运经停） */
  serviceMode?: string
  /** 任务段状态（TaskItemStatusEnum） */
  status?: number
}

/** 调度方案详情(对应 DispatchPlanRespVO) */
export interface DispatchPlanRespVO extends DispatchPlanVO {
  items?: DispatchPlanItemVO[]
  /** 摘要（后端 getPlan 计算）：一键智能调度结果卡用 */
  depotStationName?: string
  orderCount?: number
  vehicleCount?: number
}

/** 订单归集请求（orderIds 优先；batchStart/batchEnd 兼容按时间范围，毫秒时间戳） */
export interface DispatchCollectReqVO {
  orderIds?: number[]
  batchStart?: number
  batchEnd?: number
  /** 一键归集：true=把当前所有「待入池」订单全部入池（演示/批量场景，免勾选） */
  all?: boolean
}

/** 手工派单请求 */
export interface DispatchManualPlanReqVO {
  depotStationId: number
  vehicleId: number
  orderIds: number[]
}

/** 智能派单请求(scenario 仅供 Mock 联调,界面不暴露) */
export interface DispatchSmartPlanReqVO {
  /** 一键智能调度：后端自动选场站/车辆，忽略 depotStationId/vehicleIds */
  auto?: boolean
  depotStationId?: number
  vehicleIds?: number[]
  algorithmConfig?: Record<string, any>
}

/** 智能派单前约束校验请求 */
export interface DispatchValidateReqVO {
  /** 一键智能调度：后端自动推导场站与候选车辆 */
  auto?: boolean
  depotStationId?: number
  vehicleIds?: number[]
}

/** 智能派单前约束校验响应 */
export interface DispatchValidateRespVO {
  orderStats?: {
    passengerCount?: number
    deliveryCount?: number
    pickupCount?: number
    parcelCount?: number
  }
  vehicles?: {
    vehicleId?: number
    plateNo?: string
    passengerCapacity?: number
    cargoCapacity?: number
  }[]
  capacityCheck?: {
    totalPassengerCapacity?: number
    totalCargoCapacity?: number
    passengerExceed?: number
    cargoExceed?: number
    overCapacity?: boolean
  }
  markers?: {
    stationId?: number
    stationName?: string
    longitude?: number
    latitude?: number
    types?: string[]
    orderCount?: number
  }[]
  timeSeqIssues?: {
    orderId?: number
    orderNo?: string
    issue?: string
  }[]
  /** 自动模式回显：是否自动、本次场站、可用车辆总数 */
  auto?: boolean
  depotStationId?: number
  depotStationName?: string
  availableVehicleCount?: number
}

/** 方案审核请求 */
export interface DispatchPlanReviewReqVO {
  planId: number
  approve: boolean
  reason?: string
}

/** 发车核验请求 */
export interface DispatchCheckReqVO {
  planId: number
  vehicleId: number
  pass: boolean
  remark?: string
}

/** 分页查询调度订单池 */
export const getDispatchPoolPage = (params: PageParam & { status?: number; orderType?: number; createTime?: string[] }) => {
  return request.get({ url: '/transport/dispatch/order-pool/page', params })
}

/** 归集订单入池 */
export const collectOrders = (data: DispatchCollectReqVO): Promise<number> => {
  return request.post({ url: '/transport/dispatch/order-pool/collect', data })
}

/** 手工派单 */
export const createManualPlan = (data: DispatchManualPlanReqVO): Promise<number> => {
  return request.post({ url: '/transport/dispatch/plan/manual', data })
}

/** 智能派单 */
export const createSmartPlan = (data: DispatchSmartPlanReqVO): Promise<number> => {
  return request.post({ url: '/transport/dispatch/plan/smart', data })
}

/** 智能派单前约束校验（订单统计/运力预警/站点标记/时序检查） */
export const validateDispatch = (data: DispatchValidateReqVO): Promise<DispatchValidateRespVO> => {
  return request.post({ url: '/transport/dispatch/validate', data })
}

/** 分页查询调度方案 */
export const getDispatchPlanPage = (params: PageParam & { status?: number; mode?: number; createTime?: string[] }) => {
  return request.get({ url: '/transport/dispatch/plan/page', params })
}

/** 获取调度方案详情(含经停序列) */
export const getDispatchPlan = (id: number): Promise<DispatchPlanRespVO> => {
  return request.get({ url: '/transport/dispatch/plan/get', params: { id } })
}

/** 方案道路轨迹点（GCJ-02） */
export interface DispatchRoadmapPoint {
  longitude?: number
  latitude?: number
}

/** 方案分段道路轨迹（上一站 → 本站） */
export interface DispatchRoadmapSegment {
  vehicleId?: number
  visitSequence?: number
  fromStationId?: number
  toStationId?: number
  fromStationName?: string
  toStationName?: string
  /** AMAP 真实道路 / EUCLIDEAN 直线兜底 */
  provider?: string
  points?: DispatchRoadmapPoint[]
}

/** 方案真实道路地图数据 */
export interface DispatchRoadmapRespVO {
  planId?: number
  /** AMAP / EUCLIDEAN / MIXED */
  provider?: string
  segments?: DispatchRoadmapSegment[]
}

/** 获取调度方案的真实道路地图数据（按车辆 + 经停序号的每段轨迹） */
export const getDispatchPlanRoadmap = (id: number): Promise<DispatchRoadmapRespVO> => {
  return request.get({ url: '/transport/dispatch/plan/roadmap', params: { id } })
}

/** 两点之间的真实道路轨迹（按订单视角画线路用；取不到返回空数组） */
export const getRoadBetween = (params: {
  fromLongitude: number
  fromLatitude: number
  toLongitude: number
  toLatitude: number
}): Promise<DispatchRoadmapPoint[]> => {
  return request.get({ url: '/transport/dispatch/plan/route-between', params })
}

/** 审核调度方案 */
export const reviewDispatchPlan = (data: DispatchPlanReviewReqVO) => {
  return request.put({ url: '/transport/dispatch/plan/review', data })
}

/**
 * 演示态：把方案里的订单放回「待入池」，方便反复点「一键演示」。
 * 生产环境把 yudao.dispatch.demo-recycle-pool 设为 false 后，本调用为空操作
 * （调度后的订单不再回到订单池，除非显式打回重新派送）。
 */
export const recycleDemoPool = (planIds?: number[]) => {
  return request.post({ url: '/transport/dispatch/demo/recycle-pool', data: planIds || [] })
}

/** 发车核验 */
export const checkDeparture = (data: DispatchCheckReqVO) => {
  return request.post({ url: '/transport/dispatch/departure-check', data })
}

/** 返程结算请求 */
export interface DispatchSettlementReqVO {
  batchStart: string
  batchEnd: string
}

/** 返程结算响应 */
export interface DispatchSettlementRespVO {
  totalDistance?: number
  passengerCount?: number
  parcelCount?: number
  avgPassengerWaitMinutes?: number
  perVehicle?: {
    vehicleId?: number
    plateNo?: string
    runCount?: number
    passengerCount?: number
    parcelCount?: number
  }[]
}

/** 返程结算（已完成方案里程/乘客/包裹/分车汇总） */
export const getDispatchSettlement = (params: DispatchSettlementReqVO): Promise<DispatchSettlementRespVO> => {
  return request.get({ url: '/transport/dispatch/settlement', params })
}
