<template>
  <div class="flex items-start gap-16px">
    <!-- 地图区 -->
    <ContentWrap class="flex-1 min-w-0 !mb-0" title="车辆实时监控">
      <div class="relative w-full h-[calc(100vh-240px)] min-h-[480px]">
        <div ref="mapRef" class="w-full h-full rounded-4px overflow-hidden" />
        <div
          v-if="!mapReady"
          class="absolute inset-0 flex items-center justify-center bg-gray-50 text-gray-400"
        >
          {{ mapError || '地图加载中...' }}
        </div>
      </div>
    </ContentWrap>

    <!-- 右侧面板 -->
    <div class="w-340px flex-shrink-0 flex flex-col gap-16px">
      <ContentWrap class="!mb-0" title="运力状态">
        <div class="flex justify-between text-center">
          <div>
            <div class="text-2xl font-bold text-green-500">{{ statusCount.inTransit }}</div>
            <div class="text-gray-500 text-sm mt-4px">在途</div>
          </div>
          <div>
            <div class="text-2xl font-bold text-blue-500">{{ statusCount.idle }}</div>
            <div class="text-gray-500 text-sm mt-4px">空闲</div>
          </div>
          <div>
            <div class="text-2xl font-bold text-gray-400">{{ statusCount.disabled }}</div>
            <div class="text-gray-500 text-sm mt-4px">停用</div>
          </div>
        </div>
      </ContentWrap>

      <ContentWrap class="!mb-0" title="今日班次">
        <el-scrollbar max-height="220px">
          <div v-if="!shifts.length" class="text-gray-400 text-sm">暂无启用班次</div>
          <div
            v-for="shift in shifts"
            :key="shift.shiftId"
            class="flex items-center justify-between py-6px border-b border-gray-100 last:border-0"
          >
            <div class="min-w-0">
              <div class="text-sm font-500 truncate">{{ shift.shiftCode }} {{ shift.routeName || '' }}</div>
              <div class="text-xs text-gray-400">
                {{ formatTime(shift.plannedDepartureTime) }} 发车 · {{ shift.plannedDurationMinutes ?? '-' }}分钟
              </div>
              <!-- 司机端真实执行记录（落库为准）：司机/车辆/当前站/已装件数 -->
              <div
                v-if="shift.driverName || shift.plateNo"
                class="text-xs mt-2px text-gray-500"
              >
                {{ shift.driverName || '-' }}{{ shift.plateNo ? ' · ' + shift.plateNo : '' }}
                <template v-if="shift.currentStationName"> · 当前 {{ shift.currentStationName }}</template>
                <template v-if="shift.loadedCount != null"> · 已装 {{ shift.loadedCount }} 件</template>
              </div>
            </div>
            <el-tag :type="shiftStatusTag(shift.status)" size="small" class="flex-shrink-0 ml-8px">
              {{ shiftStatusLabel(shift.status) }}
            </el-tag>
          </div>
        </el-scrollbar>
      </ContentWrap>

      <ContentWrap class="!mb-0" title="车辆列表">
        <el-scrollbar max-height="320px">
          <div v-if="!vehicles.length" class="text-gray-400 text-sm">暂无车辆</div>
          <div
            v-for="vehicle in vehicles"
            :key="vehicle.vehicleId"
            class="py-8px border-b border-gray-100 last:border-0 cursor-pointer hover:bg-gray-50 px-4px rounded-4px"
            @click="locateVehicle(vehicle)"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm font-500">{{ vehicle.plateNo }}</span>
              <el-tag :type="vehicleStatusTag(vehicle.status)" size="small">
                {{ vehicleStatusLabel(vehicle.status) }}
              </el-tag>
            </div>
            <div class="text-xs text-gray-400 mt-4px">
              <template v-if="vehicle.status === 1">
                {{ vehicle.shiftCode }} · {{ vehicle.routeName }} · 进度 {{ vehicle.progress ?? 0 }}%
                <template v-if="vehicle.nextStationName"> · 下一站 {{ vehicle.nextStationName }}</template>
              </template>
              <template v-else>
                {{ vehicle.driverName ? `司机 ${vehicle.driverName}` : '未绑定司机' }}
                <template v-if="vehicle.shiftCode"> · 班次 {{ vehicle.shiftCode }}</template>
              </template>
            </div>
          </div>
        </el-scrollbar>
      </ContentWrap>
    </div>
  </div>
</template>

<script setup lang="ts">
import { loadBaiduMapSdk } from '@/components/Map/src/utils'
import {
  getMonitoringMapData,
  getMonitoringVehicles,
  getShiftExecution,
  type MonitoringRouteVO,
  type MonitoringShiftVO,
  type MonitoringStationVO,
  type MonitoringVehicleVO
} from '@/api/transport/monitoring'

defineOptions({ name: 'TransportMonitoring' })

// 说明：演示站点坐标（成都一带）按百度 BD-09 坐标系直接使用；
// 将来接入真实 GPS（WGS84/GCJ-02）时需先做坐标转换再上图。

const ROUTE_COLORS = ['#409EFF', '#E6A23C', '#F56C6C', '#9C27B0', '#00BCD4', '#795548']
const POLL_INTERVAL = 10000

const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
const mapError = ref('')
const vehicles = ref<MonitoringVehicleVO[]>([])
const shifts = ref<MonitoringShiftVO[]>([])

let map: any = null
let timer: number | undefined
const vehicleOverlays = new Map<number, { marker: any; label: any }>()

const statusCount = computed(() => ({
  inTransit: vehicles.value.filter((v) => v.status === 1).length,
  idle: vehicles.value.filter((v) => v.status === 0).length,
  disabled: vehicles.value.filter((v) => v.status === 2).length
}))

const vehicleStatusLabel = (status: number) => ({ 0: '空闲', 1: '在途', 2: '停用' }[status] || '未知')
const vehicleStatusTag = (status: number): 'success' | 'info' | 'danger' =>
  ({ 1: 'success', 2: 'danger' } as Record<number, 'success' | 'info' | 'danger'>)[status] || 'info'
const shiftStatusLabel = (status: number) => ({ 0: '未发车', 1: '在途', 2: '已完成' }[status] || '未知')
const shiftStatusTag = (status: number): 'success' | 'info' | 'primary' =>
  ({ 1: 'success', 0: 'primary' } as Record<number, 'success' | 'info' | 'primary'>)[status] || 'info'
const formatTime = (time?: string) => (time ? time.slice(0, 5) : '--:--')

/** 初始化地图与图层，并启动轮询 */
const initMap = async () => {
  try {
    await loadBaiduMapSdk(15000)
  } catch {
    mapError.value =
      '地图加载失败：请检查百度地图 AK 的 Referer 白名单是否包含当前访问地址（lbsyun.baidu.com 控制台），或检查网络后刷新'
    return
  }
  try {
    const data = await getMonitoringMapData()
    const BMapGL = window.BMapGL
    map = new BMapGL.Map(mapRef.value)
    const center = calcCenter(data.stations)
    map.centerAndZoom(new BMapGL.Point(center.lng, center.lat), 13)
    map.enableScrollWheelZoom()
    drawStations(data.stations)
    data.routes.forEach((route, index) => drawRoute(route, ROUTE_COLORS[index % ROUTE_COLORS.length]))
    mapReady.value = true
    await refreshData()
    timer = window.setInterval(refreshData, POLL_INTERVAL)
  } catch (e) {
    console.error('初始化监控地图失败', e)
    mapError.value = '监控数据加载失败，请稍后重试'
  }
}

/** 站点坐标均值作为地图中心；无站点时默认成都 */
const calcCenter = (stations: MonitoringStationVO[]) => {
  const valid = stations.filter((s) => s.longitude && s.latitude)
  if (!valid.length) return { lng: 104.0657, lat: 30.5723 }
  return {
    lng: valid.reduce((sum, s) => sum + s.longitude, 0) / valid.length,
    lat: valid.reduce((sum, s) => sum + s.latitude, 0) / valid.length
  }
}

const drawStations = (stations: MonitoringStationVO[]) => {
  const BMapGL = window.BMapGL
  stations.forEach((station) => {
    if (!station.longitude || !station.latitude) return
    const point = new BMapGL.Point(station.longitude, station.latitude)
    map.addOverlay(new BMapGL.Marker(point))
    const label = new BMapGL.Label(station.stationName, {
      position: point,
      offset: new BMapGL.Size(8, -8)
    })
    label.setStyle({
      color: '#606266',
      backgroundColor: 'rgba(255,255,255,0.9)',
      border: '1px solid #dcdfe6',
      borderRadius: '3px',
      padding: '1px 4px',
      fontSize: '11px'
    })
    map.addOverlay(label)
  })
}

const drawRoute = (route: MonitoringRouteVO, color: string) => {
  const BMapGL = window.BMapGL
  const path = route.points
    .filter((p) => p.longitude && p.latitude)
    .map((p) => new BMapGL.Point(p.longitude, p.latitude))
  if (path.length < 2) return
  map.addOverlay(
    new BMapGL.Polyline(path, {
      strokeColor: color,
      strokeWeight: 4,
      strokeOpacity: 0.75
    })
  )
}

/** 轮询刷新车辆位置与班次执行状态 */
const refreshData = async () => {
  try {
    const [vehicleList, shiftList] = await Promise.all([getMonitoringVehicles(), getShiftExecution()])
    vehicles.value = vehicleList
    shifts.value = shiftList
    refreshVehicleOverlays(vehicleList)
  } catch (e) {
    console.error('刷新监控数据失败', e)
  }
}

/** 车辆覆盖物增量更新：新车添加、旧车移动、无坐标车移除 */
const refreshVehicleOverlays = (list: MonitoringVehicleVO[]) => {
  if (!map) return
  const BMapGL = window.BMapGL
  const seen = new Set<number>()
  list.forEach((vehicle) => {
    seen.add(vehicle.vehicleId)
    if (vehicle.longitude == null || vehicle.latitude == null) {
      removeVehicleOverlay(vehicle.vehicleId)
      return
    }
    const point = new BMapGL.Point(vehicle.longitude, vehicle.latitude)
    let overlay = vehicleOverlays.get(vehicle.vehicleId)
    if (!overlay) {
      const marker = new BMapGL.Marker(point)
      const label = new BMapGL.Label(vehicle.plateNo, {
        position: point,
        offset: new BMapGL.Size(-18, -30)
      })
      map.addOverlay(marker)
      map.addOverlay(label)
      overlay = { marker, label }
      vehicleOverlays.set(vehicle.vehicleId, overlay)
    } else {
      overlay.marker.setPosition(point)
      overlay.label.setPosition(point)
    }
    overlay.label.setStyle(vehicleLabelStyle(vehicle.status))
  })
  ;[...vehicleOverlays.keys()].forEach((id) => {
    if (!seen.has(id)) removeVehicleOverlay(id)
  })
}

const vehicleLabelStyle = (status: number) => ({
  color: '#fff',
  backgroundColor: status === 1 ? '#67C23A' : '#909399',
  border: 'none',
  borderRadius: '3px',
  padding: '2px 6px',
  fontSize: '12px'
})

const removeVehicleOverlay = (vehicleId: number) => {
  const overlay = vehicleOverlays.get(vehicleId)
  if (!overlay || !map) return
  map.removeOverlay(overlay.marker)
  map.removeOverlay(overlay.label)
  vehicleOverlays.delete(vehicleId)
}

/** 点击车辆列表定位到地图 */
const locateVehicle = (vehicle: MonitoringVehicleVO) => {
  if (!map || vehicle.longitude == null || vehicle.latitude == null) return
  map.panTo(new window.BMapGL.Point(vehicle.longitude, vehicle.latitude))
}

onMounted(initMap)

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  vehicleOverlays.clear()
  if (map) {
    map.destroy()
    map = null
  }
})
</script>
