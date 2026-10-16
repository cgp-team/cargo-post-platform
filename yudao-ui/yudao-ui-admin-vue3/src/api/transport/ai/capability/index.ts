import request from '@/config/axios'

export interface AiCapabilityStatus {
  enabled: boolean
  endpointConfigured: boolean
  ready: boolean
  reason: string
  model?: string
}

/** 获得 AI 能力状态（只读；不发起任何模型调用） */
export const getAiCapabilityStatus = (): Promise<AiCapabilityStatus> => {
  return request.get({ url: '/transport/ai/capability/status' })
}
