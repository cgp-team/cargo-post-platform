import request from '@/config/axios'
import type { PageParam } from '@/types/common'

export interface DriverVO {
  id?: number
  name: string
  mobile?: string
  licenseNo?: string
  licenseExpireDate?: string
  createTime?: string
}

export const getDriverPage = (params: PageParam & Partial<DriverVO>) => {
  return request.get({ url: '/transport/driver/page', params })
}
export const getDriver = (id: number): Promise<DriverVO> => {
  return request.get({ url: '/transport/driver/get', params: { id } })
}
export const createDriver = (data: DriverVO) => {
  return request.post({ url: '/transport/driver/create', data })
}
export const updateDriver = (data: DriverVO) => {
  return request.put({ url: '/transport/driver/update', data })
}
export const deleteDriver = (id: number) => {
  return request.delete({ url: '/transport/driver/delete', params: { id } })
}
