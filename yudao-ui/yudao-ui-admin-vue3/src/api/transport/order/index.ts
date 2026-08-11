import request from '@/config/axios'

export interface OrderVO {
  id?: number
  orderNo?: string
  orderType?: number
  pickupStationId?: number
  deliveryStationId?: number
  earliestPickupTime?: string
  latestDeliveryTime?: string
  status?: number
  totalAmount?: number
  // Passenger
  passengerCount?: number
  contactName?: string
  contactMobile?: string
  // Cargo
  cargoCategory?: string
  freshFlag?: boolean
  cargoItemCount?: number
  cargoWeightKg?: number
  cargoVolumeM3?: number
  // Postal
  mailNo?: string
  carrierCode?: string
  postalItemCount?: number
  postalWeightKg?: number
  // Display names
  pickupStationName?: string
  deliveryStationName?: string
  // Cargo send info
  goodsName?: string
  goodsNote?: string
  photoUrl?: string
  receiverName?: string
  receiverMobile?: string
  receiverAddress?: string
  createTime?: string
}

export const getOrderPage = (params: PageParam & Partial<OrderVO>) => {
  return request.get({ url: '/transport/order/page', params })
}
export const getOrder = (id: number): Promise<OrderVO> => {
  return request.get({ url: '/transport/order/get', params: { id } })
}
export const createOrder = (data: OrderVO) => {
  return request.post({ url: '/transport/order/create', data })
}
export const updateOrder = (data: OrderVO) => {
  return request.put({ url: '/transport/order/update', data })
}
export const deleteOrder = (id: number) => {
  return request.delete({ url: '/transport/order/delete', params: { id } })
}
