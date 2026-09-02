import request from '@/config/axios'

/** 开发者状态响应 */
export interface DeveloperStatusVO {
  developerMode: boolean
  environmentSimulationEnabled: boolean
  canViewSimulation: boolean
  canControlSimulation: boolean
}

/** 获取开发者状态 */
export const getDeveloperStatus = (): Promise<DeveloperStatusVO> => {
  return request.get({ url: '/transport/developer/status' })
}

/** 开启开发者模式 */
export const enableDeveloperMode = () => {
  return request.post({ url: '/transport/developer/enable' })
}

/** 关闭开发者模式 */
export const disableDeveloperMode = () => {
  return request.post({ url: '/transport/developer/disable' })
}
