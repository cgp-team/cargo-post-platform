import request from '@/config/axios'

export interface VehicleVO {
  id?: number
  plateNo: string
  /** 当前绑定司机（simple-list 返回）：选车就能看到"这车谁开" */
  driverName?: string
  driverMobile?: string
  vehicleType?: number
  passengerCapacity?: number
  cargoCapacityKg?: number
  cargoCapacity?: number
  insuranceExpireDate?: string
  status?: number
  createTime?: string
}

export const getVehiclePage = (params: PageParam & Partial<VehicleVO>) => {
  return request.get({ url: '/transport/vehicle/page', params })
}
export const getVehicle = (id: number): Promise<VehicleVO> => {
  return request.get({ url: '/transport/vehicle/get', params: { id } })
}
export const createVehicle = (data: VehicleVO) => {
  return request.post({ url: '/transport/vehicle/create', data })
}
export const updateVehicle = (data: VehicleVO) => {
  return request.put({ url: '/transport/vehicle/update', data })
}
export const deleteVehicle = (id: number) => {
  return request.delete({ url: '/transport/vehicle/delete', params: { id } })
}

/** 获取车辆精简列表 */
export const getSimpleVehicleList = (): Promise<VehicleVO[]> => {
  return request.get({ url: '/transport/vehicle/simple-list' })
}

/** 获取 N 天内（含已过期）保险到期的车辆列表 */
export const getVehicleExpiringList = (days = 30): Promise<VehicleVO[]> => {
  return request.get({ url: '/transport/vehicle/expiring-list', params: { days } })
}
