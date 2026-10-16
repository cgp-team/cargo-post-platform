import request from '@/config/axios'

/**
 * AI 辅助任务（契约 §7 的五个需要模型的口 + §6 第 2 项那条规则版情景提示）。
 *
 * 三条口径写在这里，因为前端是它们最后一道落点：
 * · 返回体一律是**草稿**（ready/parsed/suggestion/basis），页面只显示、不自动落任何业务数据；
 * · `ready=false` 时显示"能力未就绪 + 原因"，**不许**用模板话术填成一段看起来像结论的文字；
 * · 情景提示那一格走独立权限位且不需要模型 —— 关掉 AI 它仍要能出结果（这是"AI 是增强不是主干"的活样本）。
 */
export interface AiDraftVO {
  scene?: string
  ready?: boolean
  parsed?: boolean
  reason?: string
  rawText?: string
  suggestion?: string
  basis?: string
  numbersPreserved?: boolean
}

export interface AiTaskReqVO {
  text?: string
  photoUrl?: string
  categories?: string[]
  items?: string[]
  sampleCount?: number
  day?: string
}

export const classifyReport = (data: AiTaskReqVO) =>
  request.post({ url: '/transport/ai/task/classify-report', data })

export const checkPhotoSupport = (data: AiTaskReqVO) =>
  request.post({ url: '/transport/ai/task/photo-support', data })

export const mergeReports = (data: AiTaskReqVO) =>
  request.post({ url: '/transport/ai/task/merge-reports', data })

export const clusterFeedback = (data: AiTaskReqVO) =>
  request.post({ url: '/transport/ai/task/cluster-feedback', data })

export const polishBriefing = (data: AiTaskReqVO) =>
  request.post({ url: '/transport/ai/task/polish-briefing', data })

export const getScenarioHints = (day: string) =>
  request.get({ url: '/transport/ai/task/scenario-hints?day=' + day })
