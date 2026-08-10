import request from '@/config/axios'

/** 运营摘要 */
export interface DashboardSummaryVO {
  orderTotal: number
  orderToday: number
  orderAmountTotal: number
  orderAmountToday: number
  vehicleTotal: number
  vehicleInTransit: number
  vehicleIdle: number
  vehicleDisabled: number
  driverTotal: number
  shiftTotal: number
  shiftPending: number
  shiftInTransit: number
  shiftCompleted: number
}

export interface OrderTypeDistributionVO {
  type: number
  count: number
}

export interface OrderStatusDistributionVO {
  status: number
  count: number
}

export interface OrderDailyTrendVO {
  date: string
  count: number
  amount: number
}

/** 订单统计 */
export interface OrderStatisticsVO {
  typeDistribution: OrderTypeDistributionVO[]
  statusDistribution: OrderStatusDistributionVO[]
  dailyTrend: OrderDailyTrendVO[]
}

/** 获取运营摘要 */
export const getDashboardSummary = (): Promise<DashboardSummaryVO> => {
  return request.get({ url: '/transport/dashboard/summary' })
}

/** 获取订单统计（类型/状态分布、近7日趋势） */
export const getOrderStatistics = (): Promise<OrderStatisticsVO> => {
  return request.get({ url: '/transport/dashboard/order-statistics' })
}
