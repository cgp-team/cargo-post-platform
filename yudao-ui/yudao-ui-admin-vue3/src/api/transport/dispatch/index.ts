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
}

/** 调度方案详情(对应 DispatchPlanRespVO) */
export interface DispatchPlanRespVO extends DispatchPlanVO {
  items?: DispatchPlanItemVO[]
}

/** 订单归集请求（后端 LocalDateTime 全局按毫秒时间戳序列化） */
export interface DispatchCollectReqVO {
  batchStart: number
  batchEnd: number
}

/** 手工派单请求 */
export interface DispatchManualPlanReqVO {
  depotStationId: number
  vehicleId: number
  orderIds: number[]
}

/** 智能派单请求(scenario 仅供 Mock 联调,界面不暴露) */
export interface DispatchSmartPlanReqVO {
  depotStationId: number
  vehicleIds: number[]
  algorithmConfig?: Record<string, any>
}

/** 智能派单前约束校验请求 */
export interface DispatchValidateReqVO {
  depotStationId: number
  vehicleIds: number[]
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

/** 审核调度方案 */
export const reviewDispatchPlan = (data: DispatchPlanReviewReqVO) => {
  return request.put({ url: '/transport/dispatch/plan/review', data })
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
