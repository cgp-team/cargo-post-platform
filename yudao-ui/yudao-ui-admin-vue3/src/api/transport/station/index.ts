import request from '@/config/axios'

export interface StationVO {
  id?: number
  stationCode: string
  stationName: string
  stationLevel?: number
  longitude?: number
  latitude?: number
  address?: string
  createTime?: string
}

/** 获取站点分页 */
export const getStationPage = (params: PageParam & Partial<StationVO>) => {
  return request.get({ url: '/transport/station/page', params })
}

/** 获取站点详情 */
export const getStation = (id: number): Promise<StationVO> => {
  return request.get({ url: '/transport/station/get', params: { id } })
}

/** 创建站点 */
export const createStation = (data: StationVO) => {
  return request.post({ url: '/transport/station/create', data })
}

/** 更新站点 */
export const updateStation = (data: StationVO) => {
  return request.put({ url: '/transport/station/update', data })
}

/** 删除站点 */
export const deleteStation = (id: number) => {
  return request.delete({ url: '/transport/station/delete', params: { id } })
}
