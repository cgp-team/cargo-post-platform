import request from '@/config/axios'

export interface CapacityVehicle {
  label: string
  passengerCapacity?: number
  cargoCapacityItems?: number
  cargoCapacityKg?: number
}

export interface CapacitySegment {
  name: string
  forecastPassenger?: number
  forecastCargoItems?: number
  forecastCargoKg?: number
}

export interface CapacityConflictCheckReq {
  sceneLabel?: string
  vehicles: CapacityVehicle[]
  segments: CapacitySegment[]
}

export interface CapacityConflict {
  dimension: string
  severity: string
  rated: number
  forecast: number
  exceed: number
  message: string
}

export interface CapacityConflictCheckResp {
  sceneLabel?: string
  overCapacity: boolean
  summary: string
  totalPassengerCapacity: number
  totalPassengerForecast: number
  totalCargoItemCapacity: number
  totalCargoItemForecast: number
  totalCargoKgCapacity: number
  totalCargoKgForecast: number
  conflicts: CapacityConflict[]
  bufferSuggestions: string[]
  unratedNotes: string[]
}

/** 执行一次客货空间冲突检视（只读计算，不写库） */
export const checkCapacityConflict = (data: CapacityConflictCheckReq): Promise<CapacityConflictCheckResp> => {
  return request.post({ url: '/transport/ai/capacity-conflict/check', data })
}
