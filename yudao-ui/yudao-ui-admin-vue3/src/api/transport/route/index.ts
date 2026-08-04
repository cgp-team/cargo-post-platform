import request from '@/config/axios'
import type { PageParam } from '@/types/common'

export interface RouteVO {
  id?: number
  routeCode: string
  routeName: string
  startStationId?: number
  endStationId?: number
  distanceKm?: number
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
