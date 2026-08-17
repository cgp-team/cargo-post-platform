import request from '@/config/axios'

export interface FeedbackVO {
  id?: number
  userId?: number
  name?: string
  mobile?: string
  content?: string
  status?: number
  reply?: string
  replyTime?: string
  createTime?: string
}

/** 获取意见反馈分页 */
export const getFeedbackPage = (params: PageParam & Partial<FeedbackVO>) => {
  return request.get({ url: '/transport/feedback/page', params })
}

/** 获取意见反馈详情 */
export const getFeedback = (id: number): Promise<FeedbackVO> => {
  return request.get({ url: '/transport/feedback/get', params: { id } })
}

/** 回复意见反馈 */
export const replyFeedback = (data: { id: number; reply: string }) => {
  return request.put({ url: '/transport/feedback/reply', data })
}
