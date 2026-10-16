import request from '@/config/axios'

export interface NotificationVO {
  id?: number
  userId?: number
  recipientType?: string
  level?: string
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

/** #92 管理员未处理告警分档：`total` + 每个 level 的条数（查失败要抛，不许在这层折叠成 0） */
export const getAdminUnreadCount = (): Promise<Record<string, number>> => {
  return request.get({ url: '/transport/notification/admin-unread-count' })
}

/** #92 把一条管理员告警标为已处理（后端记 readTime；表里没有操作人列，所以不记是谁点的） */
export const markAdminRead = (id: number) => {
  return request.put({ url: '/transport/notification/mark-admin-read', params: { id } })
}

// 按订单查事件时间线只有 `api/transport/orderEvent` 那一条出口（getOrderEvents）。
// 这里原来有个同端点的别名 getOrderEventTimeline，零引用 —— 两个包装打一个端点就是两份可以各自漂移的契约（队列 #68）。
