<template>
  <ContentWrap title="车辆轨迹回放" class="!mb-0">
    <!-- 筛选栏 -->
    <div class="flex items-center gap-12px mb-12px flex-wrap">
      <el-select
        v-model="queryVehicleId"
        placeholder="选择车辆"
        filterable
        clearable
        class="w-240px"
      >
        <el-option
          v-for="v in vehicles"
          :key="v.vehicleId"
          :value="v.vehicleId"
          :label="v.plateNo + (v.driverName ? ' · ' + v.driverName : '')"
        />
      </el-select>
      <el-date-picker
        v-model="queryDate"
        type="date"
        value-format="YYYY-MM-DD"
        :clearable="false"
        placeholder="选择日期"
        class="w-160px"
      />
      <el-button type="primary" :loading="loading" @click="loadTrack">查询轨迹</el-button>
      <span v-if="trackLoaded && !points.length" class="text-gray-400 text-sm">
        该车辆当日无在途轨迹
      </span>
    </div>

    <!-- 地图区 -->
    <div class="relative w-full h-[calc(100vh-320px)] min-h-[420px]">
      <div ref="mapRef" class="w-full h-full rounded-4px overflow-hidden"></div>
      <div
        v-if="!mapReady"
        class="absolute inset-0 flex items-center justify-center bg-gray-50 text-gray-400"
      >
        {{ mapError || '地图加载中...' }}
      </div>
    </div>

    <!-- 播放控制 -->
    <div v-if="points.length" class="mt-12px flex items-center gap-16px flex-wrap">
      <el-button type="primary" circle @click="togglePlay">
        <Icon :icon="playing ? 'ep:video-pause' : 'ep:video-play'" />
      </el-button>
      <el-radio-group v-model="speed" size="small">
        <el-radio-button :value="1">1x</el-radio-button>
        <el-radio-button :value="5">5x</el-radio-button>
        <el-radio-button :value="10">10x</el-radio-button>
      </el-radio-group>
      <el-slider
        v-model="currentIndex"
        :max="points.length - 1"
        :show-tooltip="false"
        class="flex-1 min-w-200px"
        @input="seek"
      />
      <div class="text-sm text-gray-500 whitespace-nowrap">
        {{ currentInfo }}
      </div>
    </div>
  </ContentWrap>
</template>

<script setup lang="ts">
import { loadBaiduMapSdk } from '@/components/Map/src/utils'
import {
  getMonitoringMapData,
  getMonitoringTrack,
  getMonitoringVehicles,
  type MonitoringRouteVO,
  type MonitoringStationVO,
  type MonitoringTrackPointVO,
  type MonitoringVehicleVO
} from '@/api/transport/monitoring'
import { formatDate } from '@/utils/formatTime'
import { useMessage } from '@/hooks/web/useMessage'

defineOptions({ name: 'TransportMonitoringReplay' })

// 说明：轨迹点为司机端上报的 GCJ-02 坐标，与实时监控页一致直接上图（BD-09 存在既有偏移，
// 坐标转换待两页统一处理，见 monitoring/index.vue 头部注释）。

const message = useMessage()

const vehicles = ref<MonitoringVehicleVO[]>([])
const queryVehicleId = ref<number>()
const queryDate = ref(formatDate(new Date()))
const loading = ref(false)
const trackLoaded = ref(false)
const points = ref<MonitoringTrackPointVO[]>([])

const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
const mapError = ref('')

const currentIndex = ref(0)
const playing = ref(false)
const speed = ref(5)

let map: any = null
let playTimer: number | undefined
let trackOverlays: any[] = []
let moveMarker: any = null

/** 当前点信息：上报时间 / 速度 / 进度 */
const currentInfo = computed(() => {
  const p = points.value[currentIndex.value]
  if (!p) return ''
  const time = p.reportTime ? p.reportTime.replace('T', ' ').slice(11, 19) : '--:--:--'
  const speedText = p.speedKmh != null ? `${p.speedKmh} km/h` : '- km/h'
  return `${time} · ${speedText} · ${currentIndex.value + 1}/${points.value.length}`
})

/** 初始化地图与背景图层（站点 + 线路），并加载车辆下拉 */
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
    drawBackground(data.stations, data.routes)
    mapReady.value = true
  } catch (e) {
    console.error('初始化回放地图失败', e)
    mapError.value = '地图数据加载失败，请稍后重试'
  }
  try {
    vehicles.value = await getMonitoringVehicles()
  } catch (e) {
    console.error('加载车辆列表失败', e)
  }
}

/** 站点坐标均值作为地图中心；无站点时默认成都 */
const calcCenter = (coords: { longitude?: number; latitude?: number }[]) => {
  const valid = coords.filter((c) => c.longitude && c.latitude)
  if (!valid.length) return { lng: 104.0657, lat: 30.5723 }
  return {
    lng: valid.reduce((sum, c) => sum + c.longitude!, 0) / valid.length,
    lat: valid.reduce((sum, c) => sum + c.latitude!, 0) / valid.length
  }
}

/** 背景图层：站点 + 线路（浅色，突出轨迹主线） */
const drawBackground = (stations: MonitoringStationVO[], routes: MonitoringRouteVO[]) => {
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
      color: '#909399',
      backgroundColor: 'rgba(255,255,255,0.85)',
      border: '1px solid #e4e7ed',
      borderRadius: '3px',
      padding: '1px 4px',
      fontSize: '11px'
    })
    map.addOverlay(label)
  })
  routes.forEach((route) => {
    const path = route.points
      .filter((p) => p.longitude && p.latitude)
      .map((p) => new BMapGL.Point(p.longitude, p.latitude))
    if (path.length < 2) return
    map.addOverlay(
      new BMapGL.Polyline(path, {
        strokeColor: '#c0c4cc',
        strokeWeight: 3,
        strokeOpacity: 0.5
      })
    )
  })
}

/** 查询并绘制轨迹 */
const loadTrack = async () => {
  if (!queryVehicleId.value) {
    message.warning('请先选择车辆')
    return
  }
  stopPlay()
  loading.value = true
  try {
    const data = await getMonitoringTrack(queryVehicleId.value, queryDate.value)
    points.value = (data.points || []).filter((p) => p.longitude != null && p.latitude != null)
    trackLoaded.value = true
    currentIndex.value = 0
    drawTrack()
  } catch (e) {
    console.error('查询轨迹失败', e)
  } finally {
    loading.value = false
  }
}

/** 绘制轨迹线 + 起终点 marker + 回放 marker，并自适应视野 */
const drawTrack = () => {
  if (!map) return
  clearTrack()
  if (!points.value.length) return
  const BMapGL = window.BMapGL
  const path = points.value.map((p) => new BMapGL.Point(p.longitude!, p.latitude!))
  trackOverlays.push(
    new BMapGL.Polyline(path, {
      strokeColor: '#409EFF',
      strokeWeight: 4,
      strokeOpacity: 0.85
    })
  )
  const startMarker = new BMapGL.Marker(path[0])
  const startLabel = new BMapGL.Label('起点', { position: path[0], offset: new BMapGL.Size(10, -10) })
  const endMarker = new BMapGL.Marker(path[path.length - 1])
  const endLabel = new BMapGL.Label('终点', {
    position: path[path.length - 1],
    offset: new BMapGL.Size(10, -10)
  })
  ;[startLabel, endLabel].forEach((label) => label.setStyle(edgeLabelStyle))
  trackOverlays.push(startMarker, startLabel, endMarker, endLabel)
  // 回放移动 marker（红色圆点样式 label 模拟，避免依赖外部图标资源）
  moveMarker = new BMapGL.Marker(path[0])
  trackOverlays.push(moveMarker)
  trackOverlays.forEach((overlay) => map.addOverlay(overlay))
  map.setViewport(path)
}

const edgeLabelStyle = {
  color: '#fff',
  backgroundColor: '#67C23A',
  border: 'none',
  borderRadius: '3px',
  padding: '2px 6px',
  fontSize: '12px'
}

/** 清除上一轮轨迹覆盖物 */
const clearTrack = () => {
  if (!map) return
  trackOverlays.forEach((overlay) => map.removeOverlay(overlay))
  trackOverlays = []
  moveMarker = null
}

/** 播放/暂停切换 */
const togglePlay = () => {
  if (playing.value) {
    stopPlay()
    return
  }
  if (currentIndex.value >= points.value.length - 1) {
    currentIndex.value = 0 // 播到结尾后再播从头开始
  }
  playing.value = true
  scheduleNext()
}

const stopPlay = () => {
  playing.value = false
  if (playTimer) {
    window.clearTimeout(playTimer)
    playTimer = undefined
  }
}

/** 按相邻点上报时间差 ÷ 倍速推进；长间隔（如跨班次）收敛到 3 秒内 */
const scheduleNext = () => {
  if (!playing.value) return
  if (currentIndex.value >= points.value.length - 1) {
    stopPlay()
    return
  }
  const cur = points.value[currentIndex.value]
  const next = points.value[currentIndex.value + 1]
  const delta = Math.max(0, parseTime(next.reportTime) - parseTime(cur.reportTime))
  const delay = Math.min(3000, Math.max(50, delta / speed.value))
  playTimer = window.setTimeout(() => {
    currentIndex.value += 1
    moveTo(currentIndex.value)
    scheduleNext()
  }, delay)
}

const parseTime = (time?: string) => (time ? new Date(time.replace(' ', 'T')).getTime() : 0)

/** 拖动进度条定位 */
const seek = (index: number) => {
  currentIndex.value = index
  moveTo(index)
}

/** 移动回放 marker 到第 index 点 */
const moveTo = (index: number) => {
  if (!map || !moveMarker) return
  const p = points.value[index]
  if (!p || p.longitude == null || p.latitude == null) return
  moveMarker.setPosition(new window.BMapGL.Point(p.longitude, p.latitude))
}

onMounted(initMap)

onUnmounted(() => {
  stopPlay()
  clearTrack()
  if (map) {
    map.destroy()
    map = null
  }
})
</script>
