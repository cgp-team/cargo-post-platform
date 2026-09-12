import request from '@/config/axios'

export interface HandoverVO {
  id?: number
  orderId?: number
  orderNo?: string
  legFromId?: number
  legToId?: number
  stationId?: number
  stationName?: string
  fromDriverId?: number
  fromDriverName?: string
  toDriverId?: number
  toDriverName?: string
  itemCount?: number
  weightKg?: number
  photoUrl?: string
  status?: number
  statusName?: string
  handoverTime?: string
  confirmTime?: string
  remark?: string
  createTime?: string
}

/** 获取货物交接分页 */
export const getHandoverPage = (params: PageParam & Partial<HandoverVO>) => {
  return request.get({ url: '/transport/handover/page', params })
}

/** 获取货物交接详情 */
export const getHandover = (id: number): Promise<HandoverVO> => {
  return request.get({ url: '/transport/handover/get', params: { id } })
}

/** 按订单获取交接记录 */
export const getHandoverListByOrder = (orderId: number): Promise<HandoverVO[]> => {
  return request.get({ url: '/transport/handover/list-by-order', params: { orderId } })
}

/** 标记交接争议 */
export const disputeHandover = (data: { id: number; remark: string }) => {
  return request.put({ url: '/transport/handover/dispute', data })
}
