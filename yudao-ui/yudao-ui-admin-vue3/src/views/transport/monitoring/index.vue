<template>
  <div class="flex items-start gap-16px">
    <!-- 地图区 -->
    <ContentWrap class="flex-1 min-w-0 !mb-0" title="车辆实时监控">
      <div class="relative w-full h-[calc(100vh-240px)] min-h-[480px]">
        <div ref="mapRef" class="w-full h-full rounded-4px overflow-hidden"></div>
        <!-- WEB-14/SEP-05：聚合态给出明确提示，避免用户误以为"车少了" -->
        <div
          v-if="mapReady && clustered"
          class="absolute top-8px left-8px px-8px py-4px rounded-4px text-xs bg-white/90 text-gray-600 shadow-sm"
        >
          点位较多，已按网格聚合显示（放大地图可查看单车）
        </div>
        <div
          v-if="!mapReady"
          class="absolute inset-0 flex items-center justify-center bg-gray-50 text-gray-500"
        >
          {{ mapError || '地图加载中...' }}
        </div>
      </div>
    </ContentWrap>

    <!-- 右侧面板 -->
    <div class="w-full lg:w-340px flex-shrink-0 flex flex-col gap-16px">
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
            <div class="text-2xl font-bold text-gray-500">{{ statusCount.disabled }}</div>
            <div class="text-gray-500 text-sm mt-4px">停用</div>
          </div>
        </div>
      </ContentWrap>

      <ContentWrap class="!mb-0" title="今日班次">
        <el-scrollbar max-height="220px">
          <div v-if="!shifts.length" class="text-gray-500 text-sm">暂无启用班次</div>
          <div
            v-for="shift in shifts"
            :key="shift.shiftId"
            class="flex items-center justify-between py-6px border-b border-gray-100 last:border-0"
          >
            <div class="min-w-0">
              <div class="text-sm font-500 truncate">{{ shift.shiftCode }} {{ shift.routeName || '' }}</div>
              <div class="text-xs text-gray-500">
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
          <div v-if="!vehicles.length" class="text-gray-500 text-sm">暂无车辆</div>
          <div
            v-for="vehicle in vehicles"
            :key="vehicle.vehicleId"
            class="py-8px border-b border-gray-100 last:border-0 cursor-pointer hover:bg-gray-50 px-4px rounded-4px"
            role="button"
            tabindex="0"
            @click="locateVehicle(vehicle)"
            @keydown.enter.prevent="locateVehicle(vehicle)"
            @keydown.space.prevent="locateVehicle(vehicle)"
          >
            <div class="flex items-center justify-between">
              <span class="text-sm font-500">{{ vehicle.plateNo }}</span>
              <el-tag :type="vehicleStatusTag(vehicle.status)" size="small">
                {{ vehicleStatusLabel(vehicle.status) }}
              </el-tag>
            </div>
            <div class="text-xs text-gray-500 mt-4px">
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
import { loadBaiduMapSdk, gcj02ToBd09, clusterByGrid, shouldCluster, clusterBubbleStyle } from '@/components/Map/src/utils'
import { vehicleStatusLabel, vehicleStatusTag, shiftStatusLabel, shiftStatusTag } from '../constants'
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

// 说明：站点/车辆坐标为 GCJ-02，上图前统一经 gcj02ToBd09 转换
// （WEB-01：注释原与代码相反——下方 drawStations/drawVehicles 实际均已转换）。

const ROUTE_COLORS = ['var(--el-color-primary)', 'var(--el-color-warning)', 'var(--el-color-danger)', 'var(--palette-3)', 'var(--palette-4)', 'var(--palette-9)']
const POLL_INTERVAL = 30000 // BE-17：轮询 10s→30s，后端聚合接口压力大；实时性由 30s 内的地图轮播补足

const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
const mapError = ref('')
const vehicles = ref<MonitoringVehicleVO[]>([])
const shifts = ref<MonitoringShiftVO[]>([])
/** 是否处于网格聚合态（WEB-14/SEP-05），用于页面上给出提示 */
const clustered = ref(false)

let map: any = null
let timer: number | undefined
const vehicleOverlays = new Map<number, { marker: any; label: any }>()
/** 聚合气泡覆盖物（与 vehicleOverlays 互斥，切换时清空） */
let clusterOverlays: any[] = []

const statusCount = computed(() => ({
  inTransit: vehicles.value.filter((v) => v.status === 1).length,
  idle: vehicles.value.filter((v) => v.status === 0).length,
  disabled: vehicles.value.filter((v) => v.status === 2).length
}))

// 车辆/班次状态标签映射已收敛到 ../constants.ts（与订单/调度共用同一套口径管理）
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
    const stations = data?.stations || []
    const routes = data?.routes || []
    const center = calcCenter(stations)
    map.centerAndZoom(new BMapGL.Point(center.lng, center.lat), 13)
    map.enableScrollWheelZoom()
    drawStations(stations)
    routes.forEach((route, index) => drawRoute(route, ROUTE_COLORS[index % ROUTE_COLORS.length]))
    mapReady.value = true
    // WEB-14：缩放改变像素网格，聚合态下需重算分桶（放大后自然散开为单车）
    map.addEventListener('zoomend', onZoomEnd)
    await refreshData() // 首次加载：失败会弹一次提示（refreshData 内部）
    timer = window.setInterval(() => refreshData(true), POLL_INTERVAL) // 轮询：静默保留旧数据
  } catch (e) {
    console.error('初始化监控地图失败', e)
    mapError.value = '监控数据加载失败，请稍后重试'
  }
}

/** 站点坐标均值作为地图中心；无站点时默认重庆邮电大学（南山·南岸区） */
const calcCenter = (stations: MonitoringStationVO[]) => {
  const valid = stations.filter((s) => s.longitude && s.latitude)
  if (!valid.length) return { lng: 106.5765, lat: 29.5325 }
  return {
    lng: valid.reduce((sum, s) => sum + s.longitude, 0) / valid.length,
    lat: valid.reduce((sum, s) => sum + s.latitude, 0) / valid.length
  }
}

const drawStations = (stations: MonitoringStationVO[]) => {
  const BMapGL = window.BMapGL
  stations.forEach((station) => {
    if (!station.longitude || !station.latitude) return
    // GCJ-02 → BD-09：百度底图不做转换会整体偏约 500m
    const bd = gcj02ToBd09(station.longitude, station.latitude)
    const point = new BMapGL.Point(bd.lng, bd.lat)
    map.addOverlay(new BMapGL.Marker(point))
    const label = new BMapGL.Label(station.stationName, {
      position: point,
      offset: new BMapGL.Size(8, -8)
    })
    label.setStyle({
      color: 'var(--el-text-color-regular)',
      backgroundColor: 'rgba(255,255,255,0.9)',
      border: '1px solid var(--el-border-color)',
      borderRadius: '3px',
      padding: '1px 4px',
      fontSize: '11px'
    })
    map.addOverlay(label)
  })
}

const drawRoute = (route: MonitoringRouteVO, color: string) => {
  const BMapGL = window.BMapGL
  // 真实道路折线优先（AMAP）：库里预热过的线路直接画真实轨迹；
  // 没预热过的线路只画"虚线示意"（站点直连），不冒充真实路线，避免出现直线乱跑。
  const road = (route.roadPoints ?? [])
    .filter((p) => p.longitude && p.latitude)
    .map((p) => {
      const bd = gcj02ToBd09(p.longitude!, p.latitude!)
      return new BMapGL.Point(bd.lng, bd.lat)
    })
  if (road.length >= 2) {
    map.addOverlay(new BMapGL.Polyline(road, {
      strokeColor: color,
      strokeWeight: 4,
      strokeOpacity: 0.75
    }))
    return
  }
  const path = (route.points ?? [])
    .filter((p) => p.longitude && p.latitude)
    .map((p) => {
      const bd = gcj02ToBd09(p.longitude!, p.latitude!)
      return new BMapGL.Point(bd.lng, bd.lat)
    })
  if (path.length < 2) return
  map.addOverlay(
    new BMapGL.Polyline(path, {
      strokeColor: 'var(--el-border-color-darker)',
      strokeWeight: 2,
      strokeOpacity: 0.7,
      strokeStyle: 'dashed'
    })
  )
}

/** 轮询刷新车辆位置与班次执行状态；WEB-10: silent=true 时仅记日志（弱网轮询不轰炸），首次加载失败弹一次 */
const refreshData = async (silent = false) => {
  try {
    const [vehicleList, shiftList] = await Promise.all([getMonitoringVehicles(), getShiftExecution()])
    vehicles.value = vehicleList || []
    shifts.value = shiftList || []
    refreshVehicleOverlays(vehicles.value)
  } catch (e) {
    console.error('刷新监控数据失败', e)
    if (!silent) useMessage().error('监控数据加载失败，请检查网络后刷新页面')
  }
}

/** 车辆覆盖物增量更新：新车添加、旧车移动、无坐标车移除；点数超阈值改用网格聚合 */
const refreshVehicleOverlays = (list: MonitoringVehicleVO[]) => {
  if (!map) return
  const withCoord = list.filter((v) => v.longitude != null && v.latitude != null)
  // 无坐标车辆：清掉它可能残留的覆盖物
  list.filter((v) => v.longitude == null || v.latitude == null).forEach((v) => removeVehicleOverlay(v.vehicleId))

  clustered.value = shouldCluster(withCoord.length)
  if (clustered.value) {
    renderClustered(withCoord)
    return
  }
  clearClusters()
  renderIndividually(withCoord)
}

/** 逐车渲染（低密度场景）：标签全部可读，支持增量移动 */
const renderIndividually = (list: MonitoringVehicleVO[]) => {
  const BMapGL = window.BMapGL
  const seen = new Set<number>()
  list.forEach((vehicle) => {
    seen.add(vehicle.vehicleId)
    const vBd = gcj02ToBd09(vehicle.longitude!, vehicle.latitude!)
    const point = new BMapGL.Point(vBd.lng, vBd.lat)
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

/**
 * 网格聚合渲染（WEB-14 / SEP-05）。
 * 用 `map.pointToPixel` 把点位投到屏幕像素，按 CLUSTER_GRID_PX 分桶：
 * 单点桶仍画单车标记（保留车牌标签），多点桶画数量气泡（点击放大两级）。
 * 由于投影随 zoom 变化，放大后点位自然散开，无需额外层级规则。
 */
const renderClustered = (list: MonitoringVehicleVO[]) => {
  const BMapGL = window.BMapGL
  clearIndividualMarkers()
  clearClusters()
  const points = list.map((vehicle) => {
    const bd = gcj02ToBd09(vehicle.longitude!, vehicle.latitude!)
    return { lng: bd.lng, lat: bd.lat, vehicle }
  })
  clusterByGrid(points, (p) => {
    const px = map.pointToPixel(new BMapGL.Point(p.lng, p.lat))
    return { x: px.x, y: px.y }
  }).forEach((bucket) => {
    const point = new BMapGL.Point(bucket.lng, bucket.lat)
    if (bucket.items.length === 1) {
      const vehicle = bucket.items[0].vehicle
      const marker = new BMapGL.Marker(point)
      const label = new BMapGL.Label(vehicle.plateNo, {
        position: point,
        offset: new BMapGL.Size(-18, -30)
      })
      label.setStyle(vehicleLabelStyle(vehicle.status))
      map.addOverlay(marker)
      map.addOverlay(label)
      clusterOverlays.push(marker, label)
      return
    }
    const bubble = new BMapGL.Label(String(bucket.items.length), {
      position: point,
      offset: new BMapGL.Size(-18, -18)
    })
    bubble.setStyle(clusterBubbleStyle(bucket.items.length))
    bubble.addEventListener?.('click', () => {
      map.setZoom(Math.min(map.getZoom() + 2, 19))
      map.panTo(point)
    })
    map.addOverlay(bubble)
    clusterOverlays.push(bubble)
  })
}

/** 移除逐车覆盖物（切换到聚合态时调用） */
const clearIndividualMarkers = () => {
  if (!map) return
  vehicleOverlays.forEach(({ marker, label }) => {
    map.removeOverlay(marker)
    map.removeOverlay(label)
  })
  vehicleOverlays.clear()
}

/** 移除聚合气泡 */
const clearClusters = () => {
  if (!map) return
  clusterOverlays.forEach((overlay) => map.removeOverlay(overlay))
  clusterOverlays = []
}

const vehicleLabelStyle = (status: number) => ({
  color: 'var(--text-on-primary)',
  backgroundColor: status === 1 ? 'var(--el-color-success)' : 'var(--el-text-color-secondary)',
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

/** 聚合态下缩放结束重算网格（非聚合态无需重算，逐车标记自带位置） */
const onZoomEnd = () => {
  if (clustered.value) refreshVehicleOverlays(vehicles.value)
}

/** 点击车辆列表定位到地图（聚合态下同样有效：直接平移，不依赖单车标记是否存在） */
const locateVehicle = (vehicle: MonitoringVehicleVO) => {
  if (!map || vehicle.longitude == null || vehicle.latitude == null) return
  const bd = gcj02ToBd09(vehicle.longitude, vehicle.latitude)
  map.panTo(new window.BMapGL.Point(bd.lng, bd.lat))
}

onMounted(initMap)

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  vehicleOverlays.clear()
  clusterOverlays = []
  if (map) {
    map.removeEventListener?.('zoomend', onZoomEnd)
    map.destroy()
    map = null
  }
})
</script>
