<template>
  <div class="simulation-container">
    <!-- 顶部状态栏 -->
    <div class="simulation-header">
      <div class="header-left">
        <span class="header-title">模拟运营实验室</span>
        <el-tag v-if="runtime" :type="statusTagType" size="small" effect="dark" class="ml-12px">
          {{ runtime.statusName }}
        </el-tag>
        <el-tag v-if="runtime?.dataSource" type="info" size="small" class="ml-8px">
          {{ runtime.dataSource }}
        </el-tag>
      </div>
      <div class="header-right">
        <template v-if="runtime">
          <span class="header-info">方案 #{{ runtime.planId }}</span>
          <span class="header-info">{{ runtime.plateNo || '车辆' + runtime.vehicleId }}</span>
          <span class="header-info highlight">{{ runtime.multiplier }}×</span>
          <span class="header-info">{{ fmtSeconds(runtime.simSeconds) }} / {{ fmtSeconds(runtime.totalSimSeconds) }}</span>
        </template>
        <el-button link type="primary" @click="goDeveloper" class="ml-16px">
          <Icon icon="ep:arrow-left" />返回
        </el-button>
      </div>
    </div>

    <!-- 权限检查 -->
    <el-alert v-if="!developerMode" type="warning" :closable="false" show-icon class="mx-16px mt-8px">
      <template #default>
        模拟运营需要先开启开发者模式。
        <el-button link type="primary" @click="goDeveloper">返回开发者中心</el-button>
      </template>
    </el-alert>
    <el-alert v-else-if="!environmentSimulationEnabled" type="info" :closable="false" show-icon class="mx-16px mt-8px">
      <template #default>
        模拟环境由服务器配置 SIMULATION_ENABLED 控制，当前为关闭状态。
      </template>
    </el-alert>
    <el-alert v-else-if="!canViewSimulation" type="error" :closable="false" show-icon class="mx-16px mt-8px">
      <template #default>
        当前账号没有模拟运营查看权限，请联系管理员分配 transport:simulation:view 权限。
      </template>
    </el-alert>

    <!-- 主体区域 -->
    <div v-if="canViewSimulation" class="simulation-body">
      <!-- 左侧面板 30% -->
      <div class="simulation-sidebar">
        <!-- 选择方案 -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">选择方案</span>
            <el-button link type="primary" size="small" @click="getPlanList">
              <Icon icon="ep:refresh" />
            </el-button>
          </div>
          <div class="panel-body">
            <el-select v-model="selectedPlanId" placeholder="选择调度方案" clearable filterable
              style="width: 100%" @change="handlePlanChange" :disabled="isRunning">
              <el-option v-for="plan in planList" :key="plan.id"
                :label="`#${plan.id} (${planStatusLabel(plan.status)})`" :value="plan.id!" />
            </el-select>
            <div v-if="selectedPlanId" class="mt-8px text-xs text-gray-400">
              方案 #{{ selectedPlanId }} · {{ vehicles.length }} 辆车
            </div>
          </div>
        </div>

        <!-- 选择车辆 -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">选择车辆</span>
          </div>
          <div class="panel-body">
            <el-select v-model="selectedVehicleId" placeholder="选择车辆" clearable
              style="width: 100%" :disabled="!selectedPlanId || isRunning">
              <el-option v-for="v in vehicles" :key="v.vehicleId"
                :label="`${v.plateNo || '车辆' + v.vehicleId}`" :value="v.vehicleId!" />
            </el-select>
          </div>
        </div>

        <!-- 模拟控制 -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">模拟控制</span>
          </div>
          <div class="panel-body">
            <div class="control-row">
              <el-button type="primary" :disabled="!canStart" @click="handleStart" :loading="controlling">
                <Icon icon="ep:video-play" />启动
              </el-button>
              <el-button :disabled="!canPause" @click="handlePause">
                <Icon icon="ep:video-pause" />暂停
              </el-button>
              <el-button :disabled="!canResume" @click="handleResume">
                <Icon icon="ep:video-play" />继续
              </el-button>
              <el-button type="danger" :disabled="!canReset" @click="handleReset">
                <Icon icon="ep:refresh-left" />重置
              </el-button>
            </div>
            <div class="mt-8px flex items-center gap-8px">
              <span class="text-xs text-gray-500">倍速:</span>
              <el-select v-model="multiplier" style="width: 80px" :disabled="!canControl" size="small">
                <el-option v-for="m in [1, 2, 5, 10, 20, 30, 60]" :key="m" :label="`${m}×`" :value="m" />
              </el-select>
              <el-button size="small" :disabled="!canControl" @click="handleSpeed">设置</el-button>
            </div>
            <div v-if="!canControl" class="mt-8px text-xs text-orange-500">
              {{ controlDisabledReason }}
            </div>
          </div>
        </div>

        <!-- 场景选择 -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">模拟场景</span>
          </div>
          <div class="panel-body">
            <el-select v-model="selectedScenarioId" placeholder="选择场景" clearable
              style="width: 100%" :disabled="!isRunning">
              <el-option v-for="s in scenarios" :key="s.id"
                :label="s.name" :value="s.id" :disabled="!s.enabled">
                <div class="flex items-center gap-8px">
                  <span>{{ s.name }}</span>
                  <el-tag v-if="s.severity === 2" type="danger" size="small">严重</el-tag>
                  <el-tag v-else-if="s.severity === 1" type="warning" size="small">警告</el-tag>
                </div>
              </el-option>
            </el-select>
            <el-button class="mt-8px" size="small" :disabled="!selectedScenarioId || !isRunning"
              @click="handleApplyScenario" style="width: 100%">
              应用场景
            </el-button>
          </div>
        </div>

        <!-- 异常注入 -->
        <div class="panel">
          <div class="panel-header">
            <span class="panel-title">异常注入</span>
          </div>
          <div class="panel-body inject-grid">
            <el-button v-for="inject in injectOptions" :key="inject.type"
              size="small" :type="inject.btnType" :disabled="!isRunning"
              @click="handleInject(inject.type)" class="inject-btn">
              {{ inject.label }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- 右侧主区域 70% -->
      <div class="simulation-main">
        <!-- 地图区域 -->
        <div class="map-container">
          <div ref="mapRef" class="map-canvas" />
          <div v-if="!mapReady" class="map-loading">
            {{ mapError || '地图加载中...' }}
          </div>
          <!-- 地图上的信息覆盖层 -->
          <div v-if="runtime" class="map-overlay">
            <div class="overlay-item">
              <span class="overlay-label">当前站</span>
              <span class="overlay-value">{{ runtime.currentStationName || '—' }}</span>
            </div>
            <div class="overlay-item">
              <span class="overlay-label">下一站</span>
              <span class="overlay-value">{{ runtime.nextStationName || '—' }}</span>
            </div>
            <div class="overlay-item">
              <span class="overlay-label">ETA</span>
              <span class="overlay-value">{{ runtime.etaMinutes ? Math.round(runtime.etaMinutes) + '分钟' : '—' }}</span>
            </div>
            <div class="overlay-item">
              <span class="overlay-label">速度</span>
              <span class="overlay-value">{{ runtime.speedKmh ? Math.round(runtime.speedKmh) + ' km/h' : '—' }}</span>
            </div>
          </div>
        </div>

        <!-- 底部面板 -->
        <div class="simulation-bottom">
          <!-- 时间轴 -->
          <div class="panel timeline-panel">
            <div class="panel-header">
              <span class="panel-title">模拟时间轴</span>
              <span class="text-xs text-gray-400">
                {{ runtime ? fmtSeconds(runtime.simSeconds) : '00:00' }} / {{ runtime ? fmtSeconds(runtime.totalSimSeconds) : '00:00' }}
              </span>
            </div>
            <div class="panel-body">
              <div class="timeline-bar">
                <div class="timeline-track">
                  <div class="timeline-progress" :style="{ transform: `scaleX(${timelinePercent / 100})` }" />
                  <div class="timeline-thumb" :style="{ left: timelinePercent + '%' }" />
                </div>
                <div class="timeline-stops" v-if="runtime?.stops">
                  <div v-for="(stop, i) in runtime.stops" :key="i"
                    class="timeline-stop" :class="stopStatusClass(stop)"
                    :style="{ left: stopPosition(i) + '%' }"
                    :title="stop.stationName">
                    <div class="stop-dot" />
                    <div class="stop-label">{{ stop.stationName }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 事件流 + 统计 -->
          <div class="bottom-columns">
            <!-- 事件流 -->
            <div class="panel events-panel">
              <div class="panel-header">
                <span class="panel-title">实时事件流</span>
                <el-tag v-if="events.length" size="small" type="info">{{ events.length }}</el-tag>
              </div>
              <div class="panel-body events-scroll">
                <div v-if="events.length === 0" class="text-center text-gray-400 text-xs py-16px">
                  暂无事件
                </div>
                <div v-for="event in events" :key="event.id" class="event-item" :class="eventSeverityClass(event.severity)">
                  <div class="event-time">{{ formatEventTime(event.createTime) }}</div>
                  <div class="event-content">
                    <span class="event-title">{{ event.title }}</span>
                    <span v-if="event.content" class="event-desc">{{ event.content }}</span>
                  </div>
                  <el-tag v-if="event.severity === 2" type="danger" size="small">严重</el-tag>
                  <el-tag v-else-if="event.severity === 1" type="warning" size="small">警告</el-tag>
                </div>
              </div>
            </div>

            <!-- 运行统计 -->
            <div class="panel stats-panel">
              <div class="panel-header">
                <span class="panel-title">运行统计</span>
              </div>
              <div class="panel-body stats-grid">
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.speedKmh ? Math.round(runtime.speedKmh) : '—' }}</div>
                  <div class="stat-label">当前速度(km/h)</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.totalSimSeconds ? fmtSeconds(runtime.totalSimSeconds) : '—' }}</div>
                  <div class="stat-label">总时长</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.simSeconds ? fmtSeconds(runtime.simSeconds) : '—' }}</div>
                  <div class="stat-label">已模拟</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.multiplier || '—' }}×</div>
                  <div class="stat-label">倍速</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.stops?.length || '—' }}</div>
                  <div class="stat-label">总站点</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ completedStops }}</div>
                  <div class="stat-label">已完成</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.distanceToNextStation ? runtime.distanceToNextStation.toFixed(1) : '—' }}</div>
                  <div class="stat-label">距下一站(km)</div>
                </div>
                <div class="stat-item">
                  <div class="stat-value">{{ runtime?.etaMinutes ? Math.round(runtime.etaMinutes) : '—' }}</div>
                  <div class="stat-label">ETA(分钟)</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { loadBaiduMapSdk } from '@/components/Map/src/utils'
import * as SimulationApi from '@/api/transport/simulation'
import type { SimulationRuntimeVO, SimulationEventVO, SimulationScenarioVO } from '@/api/transport/simulation'
import * as DeveloperApi from '@/api/transport/developer'
import * as DispatchApi from '@/api/transport/dispatch'
import * as VehicleApi from '@/api/transport/vehicle'
import { useRouter } from 'vue-router'

defineOptions({ name: 'TransportSimulation' })

const message = useMessage()
const { push } = useRouter()

// ==================== 开发者状态 ====================
const developerMode = ref(false)
const environmentSimulationEnabled = ref(false)
const canViewSimulation = ref(false)
const canControlSimulation = ref(false)

const loadDeveloperStatus = async () => {
  try {
    const dev = await DeveloperApi.getDeveloperStatus()
    developerMode.value = dev.developerMode
    environmentSimulationEnabled.value = dev.environmentSimulationEnabled
    canViewSimulation.value = dev.canViewSimulation
    canControlSimulation.value = dev.canControlSimulation
  } catch (e) {
    console.error('获取开发者状态失败', e)
  }
}

// ==================== 方案/车辆选择 ====================
const planList = ref<DispatchApi.DispatchPlanVO[]>([])
const selectedPlanId = ref<number>()
const vehicles = ref<{ vehicleId: number; plateNo?: string }[]>([])
const selectedVehicleId = ref<number>()
const multiplier = ref(10)
const controlling = ref(false)

const getPlanList = async () => {
  try {
    const res = await DispatchApi.getDispatchPlanPage({ pageNo: 1, pageSize: 100 })
    planList.value = res.list
  } catch (e) {
    console.error('加载方案失败', e)
  }
}

const handlePlanChange = async (planId: number | undefined) => {
  selectedVehicleId.value = undefined
  vehicles.value = []
  if (!planId) return
  try {
    const detail = await DispatchApi.getDispatchPlan(planId)
    const ids = [...new Set((detail.items || []).map((i) => i.vehicleId).filter(Boolean))] as number[]
    const all = await VehicleApi.getSimpleVehicleList()
    vehicles.value = ids.map((id) => ({
      vehicleId: id,
      plateNo: all.find((v) => v.id === id)?.plateNo
    }))
  } catch (e) {
    vehicles.value = []
  }
}

// ==================== 场景 ====================
const scenarios = ref<SimulationScenarioVO[]>([])
const selectedScenarioId = ref<number>()

const loadScenarios = async () => {
  try {
    scenarios.value = await SimulationApi.getSimulationScenarios()
  } catch (e) {
    console.error('加载场景失败', e)
  }
}

// ==================== 模拟运行时 ====================
const runtime = ref<SimulationRuntimeVO | null>(null)
const events = ref<SimulationEventVO[]>([])
const isRunning = computed(() => runtime.value?.status === 1 || runtime.value?.status === 2)

// ==================== 地图 ====================
const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
const mapError = ref('')
let map: any = null
let vehicleMarker: any = null
let routePolyline: any = null
let stationMarkers: any[] = []

const initMap = async () => {
  try {
    await loadBaiduMapSdk(15000)
    const BMapGL = window.BMapGL
    map = new BMapGL.Map(mapRef.value)
    map.centerAndZoom(new BMapGL.Point(104.0657, 30.5723), 13)
    map.enableScrollWheelZoom()
    mapReady.value = true
  } catch {
    mapError.value = '地图加载失败'
  }
}

const updateMapWithRuntime = (rt: SimulationRuntimeVO) => {
  if (!map || !window.BMapGL) return
  const BMapGL = window.BMapGL

  // 更新车辆位置
  if (rt.longitude && rt.latitude) {
    const point = new BMapGL.Point(rt.longitude, rt.latitude)
    if (!vehicleMarker) {
      vehicleMarker = new BMapGL.Marker(point)
      map.addOverlay(vehicleMarker)
      const label = new BMapGL.Label(rt.plateNo || '模拟车辆', {
        position: point,
        offset: new BMapGL.Size(-20, -35)
      })
      label.setStyle({
        color: '#fff',
        backgroundColor: '#409EFF',
        border: 'none',
        borderRadius: '4px',
        padding: '2px 8px',
        fontSize: '12px',
        fontWeight: 'bold'
      })
      map.addOverlay(label)
      vehicleMarker._label = label
    } else {
      vehicleMarker.setPosition(point)
      if (vehicleMarker._label) {
        vehicleMarker._label.setPosition(point)
      }
    }
    // 首次定位
    if (!vehicleMarker._centered) {
      map.panTo(point)
      vehicleMarker._centered = true
    }
  }

  // 更新路线
  if (rt.polyline && rt.polyline.length > 1) {
    if (routePolyline) {
      map.removeOverlay(routePolyline)
    }
    const path = rt.polyline.map((p: number[]) => new BMapGL.Point(p[0], p[1]))
    routePolyline = new BMapGL.Polyline(path, {
      strokeColor: '#409EFF',
      strokeWeight: 4,
      strokeOpacity: 0.7
    })
    map.addOverlay(routePolyline)
  }

  // 更新站点标记
  stationMarkers.forEach((m) => map.removeOverlay(m))
  stationMarkers = []
  if (rt.stops) {
    rt.stops.forEach((stop) => {
      if (!stop.longitude || !stop.latitude) return
      const point = new BMapGL.Point(stop.longitude, stop.latitude)
      const marker = new BMapGL.Marker(point)
      const statusColor = stop.status === 7 ? '#67C23A' : stop.status === 2 ? '#E6A23C' : '#909399'
      const label = new BMapGL.Label(stop.stationName, {
        position: point,
        offset: new BMapGL.Size(8, -8)
      })
      label.setStyle({
        color: '#333',
        backgroundColor: 'rgba(255,255,255,0.9)',
        border: `2px solid ${statusColor}`,
        borderRadius: '4px',
        padding: '2px 6px',
        fontSize: '11px'
      })
      map.addOverlay(marker)
      map.addOverlay(label)
      stationMarkers.push(marker, label)
    })
  }
}

// ==================== 模拟控制 ====================
const canControl = computed(() => {
  return developerMode.value && environmentSimulationEnabled.value && canControlSimulation.value
})

const canStart = computed(() => {
  if (!canControl.value || !selectedVehicleId.value || !selectedPlanId.value) return false
  const s = runtime.value?.status
  return s === undefined || s === 0 || s === 3 || s === 4
})

const canPause = computed(() => canControl.value && runtime.value?.status === 1)
const canResume = computed(() => canControl.value && runtime.value?.status === 2)
const canReset = computed(() => canControl.value && runtime.value !== null && runtime.value.status !== 0)

const controlDisabledReason = computed(() => {
  if (!developerMode.value) return '请先开启开发者模式'
  if (!environmentSimulationEnabled.value) return '服务器未启用模拟环境'
  if (!canControlSimulation.value) return '当前账号没有模拟控制权限'
  return ''
})

const handleStart = async () => {
  if (!selectedPlanId.value || !selectedVehicleId.value) return
  try {
    await message.confirm('确认启动模拟运行？', '启动模拟')
    controlling.value = true
    await SimulationApi.startSimulation(selectedPlanId.value, selectedVehicleId.value, multiplier.value)
    message.success('已启动模拟')
    startPolling()
  } catch (e) {
    if (e !== 'cancel') console.error('启动失败', e)
  } finally {
    controlling.value = false
  }
}

const handlePause = async () => {
  if (!selectedVehicleId.value) return
  await SimulationApi.pauseSimulation(selectedVehicleId.value)
  refreshRuntime()
}

const handleResume = async () => {
  if (!selectedVehicleId.value) return
  await SimulationApi.resumeSimulation(selectedVehicleId.value)
  refreshRuntime()
}

const handleReset = async () => {
  if (!selectedVehicleId.value) return
  try {
    await message.confirm('确认重置当前模拟运行状态？', '重置模拟')
    await SimulationApi.resetSimulation(selectedVehicleId.value)
    message.success('已重置')
    runtime.value = null
    events.value = []
    stopPolling()
    clearMap()
  } catch (e) {
    if (e !== 'cancel') console.error('重置失败', e)
  }
}

const handleSpeed = async () => {
  if (!selectedVehicleId.value) return
  await SimulationApi.setSimulationSpeed(selectedVehicleId.value, multiplier.value)
  message.success('已设置倍速')
  refreshRuntime()
}

const handleApplyScenario = async () => {
  if (!selectedVehicleId.value || !selectedScenarioId.value) return
  try {
    await SimulationApi.applySimulationScenario(selectedVehicleId.value, selectedScenarioId.value)
    message.success('已应用场景')
    refreshRuntime()
  } catch (e) {
    console.error('应用场景失败', e)
  }
}

// ==================== 异常注入 ====================
const injectOptions = [
  { type: 'FAULT', label: '车辆故障', btnType: 'danger' as const },
  { type: 'GPS_LOST', label: 'GPS 丢失', btnType: 'warning' as const },
  { type: 'DRIVER_OFFLINE', label: '司机离线', btnType: 'warning' as const },
  { type: 'CONGESTION', label: '道路拥堵', btnType: 'warning' as const },
  { type: 'DELAY', label: '临时晚点', btnType: 'info' as const },
  { type: 'ORDER_CANCEL', label: '取消订单', btnType: 'info' as const },
  { type: 'ORDER_ADD', label: '新增订单', btnType: '' as const }
]

const handleInject = async (eventType: string) => {
  if (!selectedVehicleId.value) return
  const option = injectOptions.find((o) => o.type === eventType)
  try {
    await message.confirm(`确认注入「${option?.label || eventType}」？`, '异常注入')
    await SimulationApi.injectSimulationEvent(selectedVehicleId.value, eventType)
    message.success('已注入异常事件')
    refreshRuntime()
  } catch (e) {
    if (e !== 'cancel') console.error('注入失败', e)
  }
}

// ==================== 轮询 ====================
let pollTimer: ReturnType<typeof setInterval> | null = null

const refreshRuntime = async () => {
  if (!selectedVehicleId.value) return
  try {
    const rt = await SimulationApi.getSimulationRuntime(selectedVehicleId.value)
    runtime.value = rt
    if (rt) {
      updateMapWithRuntime(rt)
      // 加载事件
      if (rt.planId) {
        try {
          const evts = await SimulationApi.getSimulationEvents(0) // 按当前运行
          events.value = evts || []
        } catch { /* ignore */ }
      }
    }
  } catch (e) {
    console.error('获取运行时状态失败', e)
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(refreshRuntime, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const clearMap = () => {
  if (vehicleMarker && map) {
    map.removeOverlay(vehicleMarker)
    if (vehicleMarker._label) map.removeOverlay(vehicleMarker._label)
    vehicleMarker = null
  }
  if (routePolyline && map) {
    map.removeOverlay(routePolyline)
    routePolyline = null
  }
  stationMarkers.forEach((m) => map?.removeOverlay(m))
  stationMarkers = []
}

// ==================== 时间轴 ====================
const timelinePercent = computed(() => {
  if (!runtime.value || !runtime.value.totalSimSeconds) return 0
  return Math.min(100, (runtime.value.simSeconds / runtime.value.totalSimSeconds) * 100)
})

const stopPosition = (index: number) => {
  if (!runtime.value?.stops?.length) return 0
  return (index / (runtime.value.stops.length - 1)) * 100
}

const completedStops = computed(() => {
  if (!runtime.value?.stops) return 0
  return runtime.value.stops.filter((s) => s.status === 7).length
})

const stopStatusClass = (stop: { status?: number }) => {
  if (stop.status === 7) return 'stop-completed'
  if (stop.status === 1 || stop.status === 2) return 'stop-current'
  return 'stop-pending'
}

// ==================== 工具函数 ====================
const statusTagType = computed((): 'info' | 'success' | 'warning' | 'danger' => {
  switch (runtime.value?.status) {
    case 0: return 'info'
    case 1: return 'success'
    case 2: return 'warning'
    case 3: return 'info'
    case 4: return 'danger'
    default: return 'info'
  }
})

const fmtSeconds = (s?: number) => {
  if (s == null) return '—'
  const m = Math.floor(s / 60)
  const sec = Math.floor(s % 60)
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

const planStatusLabel = (s?: number) => ({ 0: '待审核', 1: '已下发', 2: '执行中', 3: '已完成', 4: '已作废' })[s ?? -1] || '—'

const formatEventTime = (time?: string) => {
  if (!time) return ''
  return time.slice(11, 19)
}

const eventSeverityClass = (severity: number) => {
  if (severity === 2) return 'event-critical'
  if (severity === 1) return 'event-warning'
  return 'event-info'
}

const goDeveloper = () => push('/transport/developer')

// ==================== 生命周期 ====================
onMounted(async () => {
  await loadDeveloperStatus()
  await initMap()
  if (canViewSimulation.value) {
    getPlanList()
    loadScenarios()
  }
})

onUnmounted(() => {
  stopPolling()
  if (map) {
    map.destroy()
    map = null
  }
})
</script>

<style scoped>
.simulation-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
  overflow: hidden;
}

.simulation-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
  color: #fff;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 1px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header-info {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.7);
}

.header-info.highlight {
  color: #409EFF;
  font-weight: 600;
  font-size: 15px;
}

.simulation-body {
  flex: 1;
  display: flex;
  gap: 12px;
  padding: 12px;
  overflow: hidden;
}

.simulation-sidebar {
  width: 300px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
}

.simulation-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.panel {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid #f0f0f0;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.panel-body {
  padding: 12px 14px;
}

.control-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.inject-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}

.inject-btn {
  font-size: 12px;
}

/* 地图 */
.map-container {
  flex: 1;
  position: relative;
  min-height: 300px;
  background: #e8e8e8;
  border-radius: 8px;
  overflow: hidden;
}

.map-canvas {
  width: 100%;
  height: 100%;
}

.map-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  color: #909399;
}

.map-overlay {
  position: absolute;
  top: 12px;
  right: 12px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 8px;
  padding: 12px 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  display: flex;
  gap: 20px;
}

.overlay-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.overlay-label {
  font-size: 11px;
  color: #909399;
  margin-bottom: 4px;
}

.overlay-value {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

/* 底部 */
.simulation-bottom {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.timeline-panel {
  width: 100%;
}

.timeline-bar {
  position: relative;
  padding: 20px 0 30px;
}

.timeline-track {
  height: 6px;
  background: #e4e7ed;
  border-radius: 3px;
  position: relative;
  overflow: hidden;
}

.timeline-progress {
  height: 100%;
  width: 100%;
  background: linear-gradient(90deg, #409EFF, #67C23A);
  border-radius: 3px;
  transform-origin: left;
  transition: transform 0.3s ease;
}

.timeline-thumb {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 14px;
  height: 14px;
  background: #409EFF;
  border: 3px solid #fff;
  border-radius: 50%;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.2);
  transition: left 0.3s ease;
}

.timeline-stops {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 100%;
}

.timeline-stop {
  position: absolute;
  top: -2px;
  transform: translateX(-50%);
}

.stop-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 2px solid #fff;
  margin: 0 auto;
}

.stop-completed .stop-dot { background: #67C23A; }
.stop-current .stop-dot { background: #E6A23C; }
.stop-pending .stop-dot { background: #C0C4CC; }

.stop-label {
  font-size: 10px;
  color: #909399;
  white-space: nowrap;
  text-align: center;
  margin-top: 4px;
}

.bottom-columns {
  display: flex;
  gap: 8px;
}

.events-panel {
  flex: 1;
  min-width: 0;
}

.events-scroll {
  max-height: 200px;
  overflow-y: auto;
}

.event-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid #f5f5f5;
}

.event-item:last-child {
  border-bottom: none;
}

.event-time {
  font-size: 12px;
  color: #909399;
  font-family: monospace;
  flex-shrink: 0;
  min-width: 70px;
}

.event-content {
  flex: 1;
  min-width: 0;
}

.event-title {
  font-size: 13px;
  font-weight: 500;
  color: #303133;
}

.event-desc {
  font-size: 12px;
  color: #909399;
  margin-left: 8px;
}

.event-critical .event-time { color: #F56C6C; font-weight: 600; }
.event-warning .event-time { color: #E6A23C; font-weight: 600; }
.event-info .event-time { color: #909399; }

.stats-panel {
  width: 300px;
  flex-shrink: 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.stat-item {
  text-align: center;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

/* 响应式 */
@media (max-width: 1200px) {
  .simulation-sidebar {
    width: 260px;
  }
  .stats-panel {
    width: 260px;
  }
}

@media (max-width: 900px) {
  .simulation-body {
    flex-direction: column;
  }
  .simulation-sidebar {
    width: 100%;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .simulation-sidebar .panel {
    flex: 1;
    min-width: 200px;
  }
  .bottom-columns {
    flex-direction: column;
  }
  .stats-panel {
    width: 100%;
  }
}
</style>
