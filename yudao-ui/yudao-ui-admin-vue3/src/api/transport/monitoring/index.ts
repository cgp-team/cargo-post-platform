import request from '@/config/axios'

/** 监控站点 */
export interface MonitoringStationVO {
  id: number
  stationCode: string
  stationName: string
  stationLevel: number
  longitude: number
  latitude: number
  address?: string
}

/** 线路途经点 */
export interface MonitoringRoutePointVO {
  sequenceNo: number
  stationId: number
  stationName: string
  longitude: number
  latitude: number
  plannedMinutes: number
}

/** 监控线路 */
export interface MonitoringRouteVO {
  id: number
  routeCode: string
  routeName: string
  distanceKm?: number
  points: MonitoringRoutePointVO[]
}

/** 地图图层数据 */
export interface MonitoringMapDataVO {
  stations: MonitoringStationVO[]
  routes: MonitoringRouteVO[]
}

/** 车辆实时位置（班次时间模拟插值） */
export interface MonitoringVehicleVO {
  vehicleId: number
  plateNo: string
  driverName?: string
  /** 0 空闲，1 在途，2 停用 */
  status: number
  longitude?: number
  latitude?: number
  shiftCode?: string
  routeName?: string
  progress?: number
  nextStationName?: string
  speedKmh?: number
}

/** 班次执行状态 */
export interface MonitoringShiftVO {
  shiftId: number
  shiftCode: string
  routeName?: string
  plannedDepartureTime?: string
  plannedDurationMinutes?: number
  /** 0 未发车，1 在途，2 已完成 */
  status: number
  driverId?: number
  driverName?: string
  vehicleId?: number
  plateNo?: string
  currentStationId?: number
  currentStationName?: string
  loadedCount?: number
  departTime?: string
  arriveTime?: string
}

/** 获取地图图层数据（站点与线路） */
export const getMonitoringMapData = (): Promise<MonitoringMapDataVO> => {
  return request.get({ url: '/transport/monitoring/map-data' })
}

/** 获取车辆实时位置 */
export const getMonitoringVehicles = (): Promise<MonitoringVehicleVO[]> => {
  return request.get({ url: '/transport/monitoring/vehicles' })
}

/** 获取今日班次执行状态 */
export const getShiftExecution = (): Promise<MonitoringShiftVO[]> => {
  return request.get({ url: '/transport/monitoring/shift-execution' })
}
