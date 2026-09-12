import request from '@/config/axios'

export interface StationVO {
  id?: number
  stationCode: string
  stationName: string
  stationLevel?: number
  longitude?: number
  latitude?: number
  address?: string
  status?: number // 0=启用 1=停用
  sourceType?: string // REAL / PROJECT / SIMULATION
  stationType?: string // BUS_STOP / CARGO_STATION / MIXED
  userAccess?: boolean // 用户可达
  vehicleAccess?: boolean // 车辆可达（校园禁行区 false）
  dispatchEnabled?: boolean // 是否可用于调度
  sort?: number
  remark?: string
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

/** 获取站点精简列表 */
export const getSimpleStationList = (): Promise<StationVO[]> => {
  return request.get({ url: '/transport/station/simple-list' })
}
