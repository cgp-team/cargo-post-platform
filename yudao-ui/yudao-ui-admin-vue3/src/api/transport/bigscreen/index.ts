import request from '@/config/axios'
import type {
  MonitoringMapDataVO,
  MonitoringVehicleVO
} from '@/api/transport/monitoring'

/** 大屏 · 24 小时趋势点 */
export interface BigScreenTrendPointVO {
  date: string
  count: number
  amount: number
}

/** 大屏 · 分布项 */
export interface BigScreenDistributionVO {
  type?: number
  status?: number
  count: number
}

/** 大屏 · 今日班次（复用监控班次结构） */
export type BigScreenShiftVO = {
  shiftId: number
  shiftCode: string
  routeName?: string
  plannedDepartureTime?: string
  plannedDurationMinutes?: number
  status: number
  driverId?: number
  driverName?: string
  plateNo?: string
  currentStationName?: string
  loadedCount?: number
  departTime?: string
  arriveTime?: string
}

/** 大屏 · 聚合响应（GET /transport/bigscreen/overview，后端缓存 48s） */
export interface BigScreenOverviewVO {
  generatedAt: string
  orderTotal: number
  orderToday: number
  orderAmountTotal: number
  orderAmountToday: number
  vehicleTotal: number
  stationCount: number
  routeCount: number
  driverTotal: number
  shiftSummary: { total: number; pending: number; inTransit: number; completed: number }
  shiftExecution: BigScreenShiftVO[]
  /** 返程结算；查询失败时为 null（前端如实标注，不伪造） */
  settlement: null | {
    totalDistance?: number
    passengerCount?: number
    parcelCount?: number
    avgPassengerWaitMinutes?: number
  }
  hourlyTrend: BigScreenTrendPointVO[]
  typeDistribution: BigScreenDistributionVO[]
  statusDistribution: BigScreenDistributionVO[]
}

/** 获取大屏聚合数据（T2 层，60s 轮询；后端 Redis 缓存 48s 挡并发） */
export const getBigScreenOverview = (): Promise<BigScreenOverviewVO> => {
  return request.get({ url: '/transport/bigscreen/overview' })
}

/** 大屏地图图层数据（T1 层，5min 轮询） */
export const getBigScreenMapData = (): Promise<MonitoringMapDataVO> => {
  return request.get({ url: '/transport/monitoring/map-data' })
}

/** 大屏车辆实时位置（T3 层，15s 轮询；后端缓存 12s） */
export const getBigScreenVehicles = (): Promise<MonitoringVehicleVO[]> => {
  return request.get({ url: '/transport/monitoring/vehicles' })
}
