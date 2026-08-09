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

/** 订单归集请求 */
export interface DispatchCollectReqVO {
  batchStart: string
  batchEnd: string
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
