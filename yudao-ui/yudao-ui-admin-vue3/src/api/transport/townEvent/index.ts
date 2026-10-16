import request from '@/config/axios'

/**
 * 乡镇事件日历（队列 #93 / 台账 D-136）。
 *
 * 这张表是"赶集日提前留货舱"这类情景提示的唯一数据来源：平台里原本没有乡镇活动这条时间轴，
 * 而订单是滞后量（先赶集、才产生寄件）。所以这一格的运维入口不是可有可无的表单 ——
 * **填不出来的表等于没有表**，这也是后端把 `recurrence`（NONE / EVERY_N_DAYS）做进表结构的原因：
 * 县域场期是周期的（多数场镇逢 1/4/7 或 2/5/8 日赶场），只能存单日的话运营要替每个镇一年填几十行。
 *
 * ⚠ 只导出页面真的在用的函数：`scripts/check_admin_api_dead_exports.py` 把死导出算作缺陷（台账 D-134）。
 */
export interface TownEventVO {
  id?: number
  town: string
  eventType: string
  startDate: string
  endDate: string
  recurrence?: string
  intervalDays?: number
  note?: string
  createTime?: string
}

/** 分页查询：`from` 是"只看这天及其之后的事件（含周期展开后的落点）" */
export const getTownEventPage = (params: any) =>
  request.get({ url: '/transport/town-event/page', params })

export const getTownEvent = (id: number) =>
  request.get({ url: '/transport/town-event/get?id=' + id })

/** 某一天生效的事件（后端把 recurrence 展开，前端不要再自己算周期） */
export const getEffectiveTownEvents = (day: string) =>
  request.get({ url: '/transport/town-event/effective-on?day=' + day })

export const createTownEvent = (data: TownEventVO) =>
  request.post({ url: '/transport/town-event/create', data })

export const updateTownEvent = (data: TownEventVO) =>
  request.put({ url: '/transport/town-event/update', data })

export const deleteTownEvent = (id: number) =>
  request.delete({ url: '/transport/town-event/delete?id=' + id })
