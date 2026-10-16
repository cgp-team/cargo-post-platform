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
  /** 真实道路轨迹点（AMAP 时非空；前端优先用它画线） */
  navigationPolyline?: { longitude: number; latitude: number }[]
  estimatedArrival?: string
  /** 计划离站时刻（任务段该段的出发时间，前端用于展示"几点到几点"） */
  estimatedDeparture?: string
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



/** 异常重调度：只重新规划受影响的运输段（已完成段不动） */
export const replanLeg = (legId: number, remark?: string) => {
  return request.post({ url: '/transport/topology/replan-leg', params: { legId, remark } })
}
