import request from '@/config/axios'

export interface RouteVO {
  id?: number
  routeCode: string
  routeName: string
  startStationId?: number
  endStationId?: number
  distanceKm?: number
  status?: number // 0=启用 1=停用
  sourceType?: string // REAL / PROJECT
  serviceType?: string // PASSENGER / CARGO / MIXED
  dispatchEnabled?: boolean
  createTime?: string
}

export const getRoutePage = (params: PageParam & Partial<RouteVO>) => {
  return request.get({ url: '/transport/route/page', params })
}
export const getRoute = (id: number): Promise<RouteVO> => {
  return request.get({ url: '/transport/route/get', params: { id } })
}
export const createRoute = (data: RouteVO) => {
  return request.post({ url: '/transport/route/create', data })
}
export const updateRoute = (data: RouteVO) => {
  return request.put({ url: '/transport/route/update', data })
}
export const deleteRoute = (id: number) => {
  return request.delete({ url: '/transport/route/delete', params: { id } })
}

/** 获取线路精简列表 */
export const getSimpleRouteList = (): Promise<RouteVO[]> => {
  return request.get({ url: '/transport/route/simple-list' })
}

/** 线路经停站点（站序） */
export interface RouteStationVO {
  stationId?: number
  stationName?: string
  address?: string
  longitude?: number
  latitude?: number
  sequenceNo?: number
  plannedMinutes?: number
}

/** 获取线路经停站点序列 */
export const getRouteStations = (routeId: number): Promise<RouteStationVO[]> => {
  return request.get({ url: '/transport/route/stations', params: { routeId } })
}

/** 保存线路经停站点序列（整线覆盖，顺序即数组顺序） */
export const saveRouteStations = (data: {
  routeId?: number
  stations: { stationId: number; plannedMinutes?: number }[]
}) => {
  return request.put({ url: '/transport/route/stations', data })
}
