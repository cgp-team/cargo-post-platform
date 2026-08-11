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
  createTime?: string
  items?: ProductOrderItemVO[]
}

export const getProductOrderPage = (params: PageParam & Partial<ProductOrderVO>) => {
  return request.get({ url: '/transport/product-order/page', params })
}
export const getProductOrder = (id: number): Promise<ProductOrderVO> => {
  return request.get({ url: '/transport/product-order/get', params: { id } })
}
export const shipProductOrder = (id: number) => {
  return request.put({ url: '/transport/product-order/ship', params: { id } })
}
export const completeProductOrder = (id: number) => {
  return request.put({ url: '/transport/product-order/complete', params: { id } })
}
