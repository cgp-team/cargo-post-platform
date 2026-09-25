import request from '@/config/axios'

/** 获取运营统计数据 */
export const getDashboardStatistics = async (): Promise<Record<string, number>> => {
  return await request.get({ url: '/transport/dashboard/statistics' })
}
