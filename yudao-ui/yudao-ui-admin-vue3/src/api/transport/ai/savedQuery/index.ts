import request from '@/config/axios'

export interface AiSavedQueryVO {
  id?: number
  name: string
  scene: string
  conditionsJson: string
  remark?: string
  createTime?: string
}

export interface BriefingMetric {
  name: string
  value: string
  unit?: string
  note?: string
}

export interface BriefingReq {
  title: string
  periodLabel?: string
  metrics: BriefingMetric[]
  notes?: string[]
}

export interface BriefingResp {
  title: string
  generatedAt: string
  markdown: string
  metricCount: number
}

/** 获取保存的查询条件分页 */
export const getSavedQueryPage = (params: PageParam & Partial<AiSavedQueryVO>) => {
  return request.get({ url: '/transport/ai/saved-query/page', params })
}

/** 获取保存的查询条件详情 */
export const getSavedQuery = (id: number): Promise<AiSavedQueryVO> => {
  return request.get({ url: '/transport/ai/saved-query/get', params: { id } })
}

/** 创建保存的查询条件 */
export const createSavedQuery = (data: AiSavedQueryVO) => {
  return request.post({ url: '/transport/ai/saved-query/create', data })
}

/** 更新保存的查询条件 */
export const updateSavedQuery = (data: AiSavedQueryVO) => {
  return request.put({ url: '/transport/ai/saved-query/update', data })
}

/** 删除保存的查询条件 */
export const deleteSavedQuery = (id: number) => {
  return request.delete({ url: '/transport/ai/saved-query/delete', params: { id } })
}

/** 生成运营简报 Markdown（只读计算） */
export const renderBriefing = (data: BriefingReq): Promise<BriefingResp> => {
  return request.post({ url: '/transport/ai/briefing/render', data })
}
