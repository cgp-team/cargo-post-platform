import request from '@/config/axios'

export interface ProductOrderItemVO {
  productId?: number
  productName?: string
  productImage?: string
  productPrice?: number
  quantity?: number
  amount?: number
}

export interface ProductOrderVO {
  id?: number
  orderNo?: string
  userId?: number
  userMobile?: string
  totalAmount?: number
  status?: number
  statusName?: string
  receiverName?: string
  receiverMobile?: string
  receiverAddress?: string
  remark?: string
  vehicleId?: number
  shiftId?: number
  // 司机执行闭环（装车 → 到站 → 妥投）
  vehiclePlate?: string
  shiftCode?: string
  driverId?: number
  driverName?: string
  driverMobile?: string
  deliverStationName?: string
  loadPhotoUrl?: string
  loadTime?: string
  deliverPhotoUrl?: string
  deliverTime?: string
  createTime?: string
  items?: ProductOrderItemVO[]
}

/** 发货请求：vehicleId/shiftId 可选，用于小程序商品溯源关联 */
export interface ProductOrderShipReqVO {
  id: number
  vehicleId?: number
  shiftId?: number
  /** 交付站点（集散中心/村级网点）：不填则默认班次线路终点站 */
  deliverStationId?: number
}

export const getProductOrderPage = (params: PageParam & Partial<ProductOrderVO>) => {
  return request.get({ url: '/transport/product-order/page', params })
}
export const getProductOrder = (id: number): Promise<ProductOrderVO> => {
  return request.get({ url: '/transport/product-order/get', params: { id } })
}
export const shipProductOrder = (data: ProductOrderShipReqVO) => {
  return request.put({ url: '/transport/product-order/ship', data })
}
export const completeProductOrder = (id: number) => {
  return request.put({ url: '/transport/product-order/complete', params: { id } })
}
