import request from '@/config/axios'

export interface AiInvocationLogVO {
  id: number
  scene: string
  operatorId?: number
  maskedInput?: string
  output?: string
  tokens?: number
  latencyMs?: number
  status: string
  errorBrief?: string
  createTime?: string
}

/** 获得 AI 调用日志分页（只读） */
export const getInvocationLogPage = (
  params: PageParam & { scene?: string; status?: string; operatorId?: number }
) => {
  return request.get({ url: '/transport/ai/invocation-log/page', params })
}
