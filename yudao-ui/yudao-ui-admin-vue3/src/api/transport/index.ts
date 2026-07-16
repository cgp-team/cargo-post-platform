import request from '@/config/axios'

/** 验证 transport 后端模块已接入。 */
export const getTransportModuleStatus = async (): Promise<string> => {
  return await request.get({ url: '/transport/test/get' })
}
