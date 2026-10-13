import request from '@/config/axios'

/**
 * 订单事件时间线（后台「快件跟踪与状态管理」那一节的读接口）。
 *
 * 后端：`OrderEventController` 只有 `GET /transport/order-event/list-by-order`，
 * 权限复用 `transport:order:query` ⇒ 这一页不需要新的菜单与权限串，挂在订单详情里即可。
 */
export interface OrderEventVO {
  id?: number
  orderId?: number
  orderNo?: string
  /** 事件类型的机器名（如 CREATE / PICKUP / DELIVER / EXCEPTION / CANCEL） */
  eventType?: string
  /** 后端 typeName() 翻好的中文名；前端不许自己按 eventType 猜一套，否则两处口径会分叉 */
  eventTypeName?: string
  /** LocalDateTime 经 YudaoJackson 注册后是 Epoch 毫秒数字，不是 ISO 串 */
  eventTime?: number
  operator?: string
  detail?: string
  extraData?: string
}

/** 按订单取事件时间线。返回空数组 = 这单确实没有事件；抛错 = 没读到（两者在界面上必须分开念） */
export const getOrderEvents = async (orderId: number) => {
  return await request.get({ url: '/transport/order-event/list-by-order?orderId=' + orderId })
}
