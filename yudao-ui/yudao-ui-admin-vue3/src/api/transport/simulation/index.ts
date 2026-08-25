import request from '@/config/axios'

/** 模拟运行状态 */
export interface SimulationStatusVO {
  vehicleId?: number
  planId?: number
  status?: number
  statusName?: string
  multiplier?: number
  simSeconds?: number
  totalSimSeconds?: number
  currentStationName?: string
  arrived?: boolean
}

/** 启动模拟：按方案(任务段)+车辆沿真实道路推进 */
export const startSimulation = (planId: number, vehicleId: number, multiplier: number) => {
  return request.post({ url: '/transport/simulation/start', params: { planId, vehicleId, multiplier } })
}

/** 暂停模拟 */
export const pauseSimulation = (vehicleId: number) => {
  return request.post({ url: '/transport/simulation/pause', params: { vehicleId } })
}

/** 恢复模拟 */
export const resumeSimulation = (vehicleId: number) => {
  return request.post({ url: '/transport/simulation/resume', params: { vehicleId } })
}

/** 重置模拟 */
export const resetSimulation = (vehicleId: number) => {
  return request.post({ url: '/transport/simulation/reset', params: { vehicleId } })
}

/** 设置倍速 */
export const setSimulationSpeed = (vehicleId: number, multiplier: number) => {
  return request.post({ url: '/transport/simulation/speed', params: { vehicleId, multiplier } })
}

/** 查询模拟运行状态（控制页轮询） */
export const getSimulationStatus = (vehicleId: number): Promise<SimulationStatusVO | null> => {
  return request.get({ url: '/transport/simulation/status', params: { vehicleId } })
}
