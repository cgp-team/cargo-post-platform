import request from '@/config/axios'

export interface NoticeVO {
  id?: number
  title: string
  content: string
  status?: number
  sort?: number
  createTime?: string
}

/** 获取公告分页 */
export const getNoticePage = (params: PageParam & Partial<NoticeVO>) => {
  return request.get({ url: '/transport/notice/page', params })
}

/** 获取公告详情 */
export const getNotice = (id: number): Promise<NoticeVO> => {
  return request.get({ url: '/transport/notice/get', params: { id } })
}

/** 创建公告 */
export const createNotice = (data: NoticeVO) => {
  return request.post({ url: '/transport/notice/create', data })
}

/** 更新公告 */
export const updateNotice = (data: NoticeVO) => {
  return request.put({ url: '/transport/notice/update', data })
}

/** 删除公告 */
export const deleteNotice = (id: number) => {
  return request.delete({ url: '/transport/notice/delete', params: { id } })
}
