import request from '@/config/axios'

// ==================== 类型定义 ====================

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

/** 模拟运行时完整状态 */
export interface SimulationRuntimeVO {
  vehicleId: number
  plateNo?: string
  planId?: number
  status: number
  statusName: string
  multiplier: number
  simSeconds: number
  totalSimSeconds: number
  longitude?: number
  latitude?: number
  speedKmh?: number
  dataSource?: string
  currentStationId?: number
  currentStationName?: string
  arrived?: boolean
  nextStationId?: number
  nextStationName?: string
  distanceToNextStation?: number
  etaMinutes?: number
  scenarioName?: string
  polyline?: number[][]
  stops?: StopInfo[]
  segmentIndex?: number
}

export interface StopInfo {
  stationId: number
  stationName: string
  longitude?: number
  latitude?: number
  visitSequence: number
  actionName?: string
  status?: number
  statusName?: string
}

/** 模拟事件 */
export interface SimulationEventVO {
  id: number
  runId: number
  eventType: string
  severity: number
  title: string
  content?: string
  simSeconds?: number
  stationName?: string
  createTime?: string
}

/** 模拟场景 */
export interface SimulationScenarioVO {
  id: number
  name: string
  description?: string
  scenarioType: string
  severity: number
  builtin: boolean
  enabled: boolean
}

/** 模拟运行历史 */
export interface SimulationRunVO {
  id: number
  planId?: number
  vehicleId?: number
  plateNo?: string
  scenarioName?: string
  multiplier?: number
  status: number
  statusName: string
  startTime?: string
  endTime?: string
  totalSimSeconds?: number
  actualSimSeconds?: number
  stationCount?: number
  completedStationCount?: number
  exceptionCount?: number
  createTime?: string
}

/** 模拟运行报告 */
export interface SimulationReportVO {
  runId: number
  planId?: number
  vehicleId?: number
  plateNo?: string
  driverName?: string
  scenarioName?: string
  multiplier?: number
  status: number
  statusName: string
  startTime?: string
  endTime?: string
  totalSimSeconds?: number
  actualSimSeconds?: number
  totalDistanceKm?: number
  actualDistanceKm?: number
  stationCount?: number
  completedStationCount?: number
  orderCount?: number
  completedOrderCount?: number
  exceptionCount?: number
  events?: SimulationEventVO[]
  onTimeRate?: number
  avgStopDuration?: number
}

/** 分页查询参数 */
export interface SimulationRunPageParams {
  pageNo?: number
  pageSize?: number
  planId?: number
  vehicleId?: number
  status?: number
}

// ==================== API ====================

/** 启动模拟 */
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

/** 查询模拟运行状态 */
export const getSimulationStatus = (vehicleId: number): Promise<SimulationStatusVO | null> => {
  return request.get({ url: '/transport/simulation/status', params: { vehicleId } })
}

/** 获取模拟运行时完整状态（地图+统计+事件一体化） */
export const getSimulationRuntime = (vehicleId: number): Promise<SimulationRuntimeVO | null> => {
  return request.get({ url: '/transport/simulation/runtime', params: { vehicleId } })
}

/** 获取模拟事件列表 */
export const getSimulationEvents = (runId: number): Promise<SimulationEventVO[]> => {
  return request.get({ url: '/transport/simulation/events', params: { runId } })
}

/** 获取可用模拟场景列表 */
export const getSimulationScenarios = (): Promise<SimulationScenarioVO[]> => {
  return request.get({ url: '/transport/simulation/scenarios' })
}

/** 应用场景到当前模拟运行 */
export const applySimulationScenario = (vehicleId: number, scenarioId: number) => {
  return request.post({ url: '/transport/simulation/scenario/apply', data: { vehicleId, scenarioId } })
}

/** 注入异常事件 */
export const injectSimulationEvent = (vehicleId: number, eventType: string, durationSeconds?: number, remark?: string) => {
  return request.post({ url: '/transport/simulation/inject', data: { vehicleId, eventType, durationSeconds, remark } })
}

/** 获取模拟运行历史 */
export const getSimulationHistory = (params: SimulationRunPageParams): Promise<{ list: SimulationRunVO[]; total: number }> => {
  return request.get({ url: '/transport/simulation/history', params })
}

/** 获取模拟运行报告 */
export const getSimulationReport = (runId: number): Promise<SimulationReportVO> => {
  return request.get({ url: '/transport/simulation/report', params: { runId } })
}

/** 删除模拟运行记录 */
export const deleteSimulationHistory = (runId: number) => {
  return request.delete({ url: '/transport/simulation/history', params: { runId } })
}
