import request from '@/config/axios'

export interface TopologyCandidate {
  mode?: string
  modeName?: string
  legCount?: number
  transferCount?: number
  distanceKm?: number
  durationMinutes?: number
  score?: number
  reason?: string
  chosen?: boolean
}

export interface TopologyLeg {
  id?: number
  legSequence?: number
  fromStationName?: string
  toStationName?: string
  fromLongitude?: number
  fromLatitude?: number
  toLongitude?: number
  toLatitude?: number
  driverName?: string
  plateNo?: string
  status?: number
  statusName?: string
  distanceKm?: number
  durationMinutes?: number
  navigationSource?: string
  estimatedArrival?: string
  actualArrival?: string
  handoverRequired?: boolean
}

export interface TopologyHandover {
  id?: number
  stationName?: string
  fromDriverName?: string
  toDriverName?: string
  fromPlateNo?: string
  toPlateNo?: string
  itemCount?: number
  status?: number
  statusName?: string
  arrivedAt?: string
  handoverCompletedAt?: string
  exceptionReason?: string
}

export interface TopologyTimeline {
  eventType?: string
  eventTypeName?: string
  eventTime?: string
  operator?: string
  detail?: string
}

export interface OrderTopologyVO {
  orderId?: number
  orderNo?: string
  orderStatusName?: string
  originStationName?: string
  destinationStationName?: string
  planningMode?: string
  planningModeName?: string
  planNo?: string
  totalLegs?: number
  transferCount?: number
  totalDistanceKm?: number
  totalDurationMinutes?: number
  planReason?: string
  candidates?: TopologyCandidate[]
  legs?: TopologyLeg[]
  handovers?: TopologyHandover[]
  timeline?: TopologyTimeline[]
}

/** 按订单查询运输拓扑 */
export const getTopologyByOrder = (orderId: number): Promise<OrderTopologyVO> => {
  return request.get({ url: '/transport/topology/order', params: { orderId } })
}

/** 按方案查询运输拓扑 */
export const getTopologyByPlan = (planId: number): Promise<OrderTopologyVO> => {
  return request.get({ url: '/transport/topology/plan', params: { planId } })
}

/** 车辆/司机时段冲突校验（手工分配前重新校验，不相信前端） */
export const checkConflict = (params: {
  vehicleId?: number
  driverId?: number
  start: string
  end: string
  excludeLegId?: number
}) => {
  return request.get({ url: '/transport/topology/conflict-check', params })
}

/** 异常重调度：只重新规划受影响的运输段（已完成段不动） */
export const replanLeg = (legId: number, remark?: string) => {
  return request.post({ url: '/transport/topology/replan-leg', params: { legId, remark } })
}
