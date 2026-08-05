import request from '@/config/axios'

export interface ShiftVO {
  id?: number
  shiftCode: string
  routeId?: number
  plannedDepartureTime?: string
  plannedDurationMinutes?: number
  status?: number
  createTime?: string
}

export const getShiftPage = (params: PageParam & Partial<ShiftVO>) => {
  return request.get({ url: '/transport/shift/page', params })
}
export const getShift = (id: number): Promise<ShiftVO> => {
  return request.get({ url: '/transport/shift/get', params: { id } })
}
export const createShift = (data: ShiftVO) => {
  return request.post({ url: '/transport/shift/create', data })
}
export const updateShift = (data: ShiftVO) => {
  return request.put({ url: '/transport/shift/update', data })
}
export const deleteShift = (id: number) => {
  return request.delete({ url: '/transport/shift/delete', params: { id } })
}
export const getSimpleShiftList = (): Promise<ShiftVO[]> => {
  return request.get({ url: '/transport/shift/simple-list' })
}
