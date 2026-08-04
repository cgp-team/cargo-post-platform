import request from '@/config/axios'

export interface VehicleVO {
  id?: number
  plateNo: string
  vehicleType?: number
  passengerCapacity?: number
  cargoCapacityKg?: number
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
