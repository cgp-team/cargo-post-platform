import request from '@/config/axios'

// ==================== 类型定义 ====================

/** 开发者状态响应 */
export interface DeveloperStatusVO {
  developerMode: boolean
  environmentSimulationEnabled: boolean
  canViewSimulation: boolean
  canControlSimulation: boolean
}

/** 服务健康状态 */
export interface ServiceHealthVO {
  name: string
  status: string
  latencyMs?: number
  checkedAt?: string
  error?: string
}

/** 系统健康检查 */
export interface DeveloperHealthVO {
  overallStatus: string
  services: ServiceHealthVO[]
}

/** 开发者中心统计 */
export interface DeveloperStatisticsVO {
  developerMode: boolean
  simulationEnabled: boolean
  runningSimulations: number
  simulatedVehicles: number
  testDataCount: number
  totalSimulations: number
  stationCount: number
  vehicleCount: number
  driverCount: number
  orderCount: number
}

// ==================== API ====================

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

/** 系统健康检查 */
export const getDeveloperHealth = (): Promise<DeveloperHealthVO> => {
  return request.get({ url: '/transport/developer/health' })
}

/** 获取开发者中心统计数据 */
export const getDeveloperStatistics = (): Promise<DeveloperStatisticsVO> => {
  return request.get({ url: '/transport/developer/statistics' })
}
