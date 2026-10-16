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

// 按订单查事件时间线只有 `api/transport/orderEvent` 那一条出口（getOrderEvents）。
// 这里原来有个同端点的别名 getOrderEventTimeline，零引用 —— 两个包装打一个端点就是两份可以各自漂移的契约（队列 #68）。
