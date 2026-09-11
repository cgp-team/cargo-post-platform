import request from '@/config/axios'

export interface NotificationVO {
  id?: number
  userId?: number
  eventType?: string
  title?: string
  content?: string
  orderId?: number
  readStatus?: number
  readTime?: string
  createTime?: string
}

/** 获取用户通知分页 */
export const getNotificationPage = (params: PageParam & Partial<NotificationVO>) => {
  return request.get({ url: '/transport/notification/page', params })
}

/** 获取用户通知详情 */
export const getNotification = (id: number): Promise<NotificationVO> => {
  return request.get({ url: '/transport/notification/get', params: { id } })
}

/** 发送用户通知 */
export const sendNotification = (data: NotificationVO) => {
  return request.post({ url: '/transport/notification/send', data })
}

/** 按订单查询事件时间线 */
export const getOrderEventTimeline = (orderId: number) => {
  return request.get({ url: '/transport/order-event/list-by-order', params: { orderId } })
}
