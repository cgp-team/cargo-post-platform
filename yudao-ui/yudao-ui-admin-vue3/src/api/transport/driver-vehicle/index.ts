import request from '@/config/axios'

/** 人车绑定 VO */
export interface DriverVehicleVO {
  id: number
  driverId: number
  driverName?: string
  vehicleId: number
  plateNo?: string
  bindTime?: number
  unbindTime?: number
  status: number
}

/** 绑定司机与车辆 */
export const bindDriverVehicle = (data: { driverId: number; vehicleId: number }) => {
  return request.post({ url: '/transport/driver-vehicle/bind', data })
}

/** 解绑（按绑定记录 id） */
export const unbindDriverVehicle = (id: number) => {
  return request.put({ url: '/transport/driver-vehicle/unbind', params: { id } })
}

/** 人车绑定分页 */
export const getDriverVehiclePage = (params: PageParam & Partial<DriverVehicleVO>) => {
  return request.get({ url: '/transport/driver-vehicle/page', params })
}

/** 查询司机全部绑定记录 */
export const getDriverVehicleListByDriver = (driverId: number) => {
  return request.get({ url: '/transport/driver-vehicle/list-by-driver', params: { driverId } })
}
