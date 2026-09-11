<template>
  <Dialog v-model="visible" title="调度结果可视化" width="1180px">
    <div v-loading="loading" class="viz">
      <!-- 方案切换 + 汇总 -->
      <div class="viz-head">
        <el-radio-group v-if="plans.length > 1" v-model="activePlanId" size="small" @change="redraw">
          <el-radio-button :value="0">全部方案</el-radio-button>
          <el-radio-button v-for="p in plans" :key="p.id" :value="p.id!">
            方案 #{{ p.id }}
          </el-radio-button>
        </el-radio-group>
        <div class="viz-summary">
          <span>方案 <b>{{ plans.length }}</b> 套</span>
          <span>订单 <b>{{ summary.orderCount }}</b> 单</span>
          <span>车辆 <b>{{ summary.vehicleCount }}</b> 台</span>
          <span>总里程 <b>{{ summary.distanceText }}</b> km</span>
        </div>
      </div>

      <div class="viz-body">
        <!-- 左：地图（加载失败 → 按真实坐标画线路示意图，演示不会中断） -->
        <div class="viz-map-wrap">
          <div ref="mapRef" class="viz-map"></div>
          <!-- 地图未就绪时用真实坐标画线路示意图覆盖在上层，演示不中断 -->
          <svg
            v-if="!mapReady"
            class="viz-svg viz-svg-overlay"
            viewBox="0 0 1000 620"
            preserveAspectRatio="xMidYMid meet"
          >
            <defs>
              <marker
                id="viz-arrow"
                viewBox="0 0 10 10"
                refX="6"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#123f6e" />
              </marker>
            </defs>
            <rect x="0" y="0" width="1000" height="620" fill="#f7f9fc" />
            <g v-for="line in svgLines" :key="line.key">
              <polyline
                :points="line.points"
                fill="none"
                :stroke="line.color"
                stroke-width="4"
                stroke-linejoin="round"
                stroke-linecap="round"
                opacity="0.85"
                marker-mid="url(#viz-arrow)"
              />
              <circle
                v-for="(pt, i) in line.dots"
                :key="i"
                :cx="pt.x"
                :cy="pt.y"
                r="6"
                :fill="line.color"
                stroke="#fff"
                stroke-width="2"
              />
              <text
                v-for="(pt, i) in line.dots"
                :key="'t' + i"
                :x="pt.x"
                :y="pt.y + 4"
                text-anchor="middle"
                font-size="10"
                fill="#fff"
              >{{ i + 1 }}</text>
            </g>
            <circle
              v-for="m in svgMovers"
              :key="m.key"
              :cx="m.x"
              :cy="m.y"
              r="9"
              :fill="m.color"
              stroke="#fff"
              stroke-width="3"
            />
          </svg>
          <div v-if="!mapReady" class="viz-map-note">
            {{ mapError ? '地图不可用，已切换为坐标示意图' : '地图加载中…' }}
          </div>
          <div class="viz-legend">
            <span class="legend-item"><span class="legend-line real"></span>真实道路</span>
            <span class="legend-item"><span class="legend-line est"></span>直线估算</span>
            <span class="legend-item"><span class="legend-arrow">➤</span>行驶方向</span>
            <span class="legend-item"><span class="legend-dot">n</span>经停顺序</span>
          </div>
          <div class="viz-playbar">
            <el-button type="primary" size="small" plain @click="togglePlay">
              <Icon :icon="playing ? 'ep:video-pause' : 'ep:video-play'" />
              {{ playing ? '暂停' : '播放路线' }}
            </el-button>
            <el-slider v-model="progress" :max="100" :show-tooltip="false" class="viz-slider" @input="onSeek" />
            <span class="viz-progress">{{ progress }}%</span>
          </div>
        </div>

        <!-- 右：每车任务段时间线 -->
        <div class="viz-timeline">
          <div v-if="!visibleRoutes.length" class="text-gray-400 text-sm">暂无调度明细</div>
          <div v-for="route in visibleRoutes" :key="route.key" class="route-card">
            <div class="route-title">
              <span class="route-color" :style="{ background: route.color }"></span>
              <span class="route-name">{{ route.title }}</span>
              <span class="route-meta">
                {{ route.stops.length }} 站 · {{ route.distanceText }} km · 订单 {{ route.orderCount }} 单
                <template v-if="route.driverText"> · 司机 {{ route.driverText }}</template>
                <template v-if="route.totalSegments">
                  · 轨迹
                  <span :class="route.realSegments === route.totalSegments ? 'trace-real' : 'trace-est'">
                    {{ route.realSegments === route.totalSegments ? '真实道路' : `真实 ${route.realSegments}/${route.totalSegments} 段` }}
                  </span>
                </template>
              </span>
            </div>
            <div v-for="(s, i) in route.stops" :key="i" class="stop-row">
              <span class="stop-seq">{{ i + 1 }}</span>
              <span class="stop-dot" :class="actionClass(s.actionType)"></span>
              <span class="stop-act" :class="actionClass(s.actionType)">{{ actionLabel(s.actionType) }}</span>
              <span class="stop-station">{{ s.stationName || stationName(s.stationId) || '-' }}</span>
              <span v-if="s.orderNo" class="stop-order">{{ s.orderNo }}</span>
              <span class="stop-time">{{ timeText(s.estimatedArrivalTime) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="visible = false">关 闭</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import { Dialog } from '@/components/Dialog'
import { loadBaiduMapSdk } from '@/components/Map/src/utils'
import * as DispatchApi from '@/api/transport/dispatch'
import * as DriverApi from '@/api/transport/driver'
import * as StationApi from '@/api/transport/station'
import * as VehicleApi from '@/api/transport/vehicle'

defineOptions({ name: 'DispatchVisualDialog' })

const props = defineProps<{ modelValue: boolean; planIds: number[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

type RouteStop = DispatchApi.DispatchPlanItemVO
interface RouteView {
  key: string
  planId?: number
  color: string
  title: string
  /** 该车涉及的订单号（去重） */
  orderNos: string[]
  orderCount: number
  stops: RouteStop[]
  /** 与 stops 下标对齐的可见坐标点（缺坐标的站会被跳过） */
  locatedStops: { stop: RouteStop; lng: number; lat: number }[]
  /** 该车司机（姓名 + 手机号）：现场演示要按手机号登录司机端，直接展示省得对不上 */
  driverText: string
  distanceKm: number
  distanceText: string
  points: { lng: number; lat: number }[]
  /** 该车轨迹中有多少段来自真实道路（AMAP）；其余为两点直线估算 */
  realSegments: number
  totalSegments: number
}

const loading = ref(false)
const plans = ref<DispatchApi.DispatchPlanRespVO[]>([])
const activePlanId = ref(0)
const routes = ref<RouteView[]>([])
const stations = ref<StationApi.StationVO[]>([])
const vehicles = ref<VehicleApi.VehicleVO[]>([])
const drivers = ref<DriverApi.DriverVO[]>([])

/** 车辆配色：多车分色，便于"一车一条线"肉眼区分 */
const ROUTE_COLORS = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399']

const stationName = (id?: number) =>
  id == null ? '' : stations.value.find((s) => s.id === id)?.stationName || ''
const stationCoord = (id?: number) => stations.value.find((s) => s.id === id)
const vehicleName = (id?: number) =>
  id == null ? '车辆' : vehicles.value.find((v) => v.id === id)?.plateNo || `车辆#${id}`

/**
 * 真实道路分段轨迹：key = `${planId}:${vehicleId}:${visitSequence}`。
 * 由后端 /transport/dispatch/plan/roadmap 按「车辆 + 经停序号」给出（高德驾车路网，带缓存）。
 */
const roadmapSegments = ref<Map<string, { provider: string; points: { lng: number; lat: number }[] }>>(new Map())

const actionLabel = (action?: number) =>
  ({ 0: '场站发车', 1: '乘客上车', 2: '乘客下车', 3: '派送', 4: '揽收', 5: '返场', 6: '途经' }[action ?? -1] || '经停')
const actionClass = (action?: number) =>
  action === 0
    ? 'act-depart'
    : action === 5
      ? 'act-return'
      : action === 4
        ? 'act-pickup'
        : action === 3
          ? 'act-deliver'
          : 'act-seat'
const timeText = (t?: string) => (t ? t.replace('T', ' ').slice(11, 16) : '')

const summary = computed(() => {
  const shown = activePlanId.value
    ? plans.value.filter((p) => p.id === activePlanId.value)
    : plans.value
  const orderIds = new Set<string>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.orderNo && orderIds.add(i.orderNo)))
  const vehicleIds = new Set<number>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.vehicleId && vehicleIds.add(i.vehicleId)))
  const distance = shown.reduce((sum, p) => sum + (Number(p.totalDistance) || 0), 0)
  return {
    orderCount: orderIds.size,
    vehicleCount: vehicleIds.size,
    distanceText: distance ? distance.toFixed(1) : '-'
  }
})

const visibleRoutes = computed(() =>
  activePlanId.value ? routes.value.filter((r) => r.planId === activePlanId.value) : routes.value
)

/** 组装"每车一条线路"：按 visitSequence 排序，累计分段里程 */
const buildRoutes = () => {
  const list: RouteView[] = []
  plans.value.forEach((plan) => {
    const byVehicle = new Map<number, RouteStop[]>()
    ;(plan.items ?? []).forEach((item) => {
      const key = item.vehicleId ?? 0
      if (!byVehicle.has(key)) byVehicle.set(key, [])
      byVehicle.get(key)!.push(item)
    })
    let colorIndex = 0
    byVehicle.forEach((stops, vehicleId) => {
      stops.sort((a, b) => (a.visitSequence ?? 0) - (b.visitSequence ?? 0))
      const orderNos = [...new Set(stops.map((s) => s.orderNo).filter(Boolean) as string[])]
      const distanceKm = stops.reduce((sum, s) => sum + (Number(s.segmentDistanceKm) || 0), 0)
      const locatedStops = stops
        .map((stop) => ({ stop, station: stationCoord(stop.stationId) }))
        .filter((x) => x.station && x.station.longitude && x.station.latitude)
        .map((x) => ({ stop: x.stop, lng: Number(x.station!.longitude), lat: Number(x.station!.latitude) }))
      const driverId = stops.map((s) => s.driverId).find((id) => id != null)
      const driver = driverId != null ? drivers.value.find((d) => d.id === driverId) : undefined
      // 轨迹点：优先拼接后端真实道路分段（AMAP），缺段则回退"上一站→本站"两点直线
      const points: { lng: number; lat: number }[] = []
      let realSegments = 0
      let totalSegments = 0
      locatedStops.forEach((located, index) => {
        const current = { lng: located.lng, lat: located.lat }
        if (index === 0) {
          points.push(current)
          return
        }
        totalSegments++
        const key = `${plan.id}:${vehicleId}:${located.stop.visitSequence ?? 0}`
        const real = roadmapSegments.value.get(key)
        if (real) {
          realSegments++
        }
        const segmentPoints = real ? real.points : [points[points.length - 1], current]
        segmentPoints.forEach((p, i) => {
          // 拼接处去掉与上段末点重复的起点
          if (i === 0) return
          points.push(p)
        })
      })
      list.push({
        key: `${plan.id}-${vehicleId}`,
        planId: plan.id,
        color: ROUTE_COLORS[colorIndex++ % ROUTE_COLORS.length],
        title: `方案 #${plan.id} · ${vehicleName(vehicleId)}`,
        orderNos,
        orderCount: orderNos.length,
        stops,
        locatedStops,
        driverText: driver ? `${driver.name}（${driver.mobile || '-'}）` : '',
        distanceKm,
        distanceText: distanceKm ? distanceKm.toFixed(1) : '-',
        points,
        realSegments,
        totalSegments
      })
    })
  })
  routes.value = list
}

// ==================== 地图（百度 BMapGL；坐标为 GCJ-02 → 上图前转 BD-09） ====================
const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
const mapError = ref('')
let map: any = null
const overlays = ref<any[]>([])
/** 播放用的车头 marker：key = 线路 key */
const moverMarkers = ref<Record<string, { marker: any; path: any[] }>>({})

const X_PI = (Math.PI * 3000.0) / 180.0
/** GCJ-02 → BD-09（百度地图底图为 BD-09，不转换会有数百米偏移） */
const gcj02ToBd09 = (lng: number, lat: number) => {
  const z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI)
  const theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI)
  return { lng: z * Math.cos(theta) + 0.0065, lat: z * Math.sin(theta) + 0.006 }
}

const initMap = async () => {
  if (map) return
  // Dialog 是懒渲染的：容器可能晚一拍才挂到 DOM，轮询等它出现（最多 ~0.5s）
  for (let i = 0; i < 10 && !mapRef.value; i++) {
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  if (!mapRef.value) {
    mapError.value = '地图容器未就绪'
    return
  }
  try {
    await loadBaiduMapSdk(8000)
  } catch {
    mapError.value = '地图 SDK 加载失败'
    return
  }
  try {
    const BMapGL = window.BMapGL
    map = new BMapGL.Map(mapRef.value)
    map.enableScrollWheelZoom()
    mapReady.value = true
  } catch (e) {
    console.error('调度可视化地图初始化失败', e)
    mapError.value = '地图初始化失败'
  }
}

const clearOverlays = () => {
  overlays.value.forEach((o) => map?.removeOverlay(o))
  overlays.value = []
  moverMarkers.value = {}
}

/** 地图上画线路 + 站点标记 + 播放用车辆 marker */
const drawMap = () => {
  if (!mapReady.value || !map) return
  const BMapGL = window.BMapGL
  clearOverlays()
  const allPoints: any[] = []
  visibleRoutes.value.forEach((route) => {
    const path = route.points.map((p) => {
      const bd = gcj02ToBd09(p.lng, p.lat)
      return new BMapGL.Point(bd.lng, bd.lat)
    })
    if (path.length < 2) return
    // 真实道路（AMAP）画实线；含直线兜底的段用虚线提示"非真实道路"
    const allReal = route.totalSegments > 0 && route.realSegments === route.totalSegments
    const polyline = new BMapGL.Polyline(path, {
      strokeColor: route.color,
      strokeWeight: 5,
      strokeOpacity: 0.9,
      strokeStyle: allReal ? 'solid' : 'dashed'
    })
    map.addOverlay(polyline)
    overlays.value.push(polyline)
    allPoints.push(...path)
    route.locatedStops.forEach((located, index) => {
      const stop = located.stop
      const bd = gcj02ToBd09(located.lng, located.lat)
      const point = new BMapGL.Point(bd.lng, bd.lat)
      const marker = new BMapGL.Circle(point, 8, { strokeColor: '#fff', strokeWeight: 2, fillColor: route.color, fillOpacity: 1 })
      map.addOverlay(marker)
      overlays.value.push(marker)
      const label = new BMapGL.Label(
        `${index + 1}. ${actionLabel(stop.actionType)} · ${stop.stationName || stationName(stop.stationId)}`,
        { position: point, offset: new BMapGL.Size(12, -24) }
      )
      label.setStyle({
        color: '#123f6e',
        fontSize: '12px',
        fontWeight: 'bold',
        border: '1px solid #c7d8ea',
        padding: '2px 6px',
        background: '#fff',
        borderRadius: '4px'
      })
      map.addOverlay(label)
      overlays.value.push(label)
    })
    // 方向箭头：沿轨迹等距放 2 个箭头（含真实道路时更直观看出行驶方向）
    directionArrows(route).forEach((arrow) => {
      const bd = gcj02ToBd09(arrow.point.lng, arrow.point.lat)
      const icon = new BMapGL.Icon(arrowIcon(route.color), new BMapGL.Size(18, 18), {
        anchor: new BMapGL.Size(9, 9)
      })
      const marker = new BMapGL.Marker(new BMapGL.Point(bd.lng, bd.lat), { icon, rotation: arrow.angle })
      if (typeof (marker as any).setRotation === 'function') {
        ;(marker as any).setRotation(arrow.angle)
      }
      map.addOverlay(marker)
      overlays.value.push(marker)
    })
    // 车头 marker（播放时沿线路移动）
    const mover = new BMapGL.Marker(path[0])
    map.addOverlay(mover)
    overlays.value.push(mover)
    moverMarkers.value[route.key] = { marker: mover, path }
  })
  if (allPoints.length) {
    map.setViewport(allPoints)
  }
}

/** 箭头图标（SVG data URI，颜色随线路） */
const arrowIcon = (color: string) => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">
    <path d="M16 3 L27 28 L16 22 L5 28 Z" fill="${color}" stroke="#ffffff" stroke-width="2" stroke-linejoin="round"/>
  </svg>`
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

/**
 * 沿轨迹按里程取若干个点，并给出该处的行驶方向角（正北为 0°，顺时针）。
 * 用于在地图上放"箭头"，让行驶方向/顺序一眼可见。
 */
const directionArrows = (route: RouteView) => {
  const points = route.points
  if (points.length < 2) return []
  const segs: number[] = []
  let total = 0
  for (let i = 1; i < points.length; i++) {
    const d = Math.hypot(points[i].lng - points[i - 1].lng, points[i].lat - points[i - 1].lat)
    segs.push(d)
    total += d
  }
  if (total <= 0) return []
  const fractions = route.realSegments > 0 ? [0.2, 0.5, 0.8] : [0.5]
  const arrows: { point: { lng: number; lat: number }; angle: number }[] = []
  fractions.forEach((fraction) => {
    let target = total * fraction
    for (let i = 0; i < segs.length; i++) {
      if (target <= segs[i] || i === segs.length - 1) {
        const t = segs[i] === 0 ? 0 : target / segs[i]
        const a = points[i]
        const b = points[i + 1]
        const lng = a.lng + (b.lng - a.lng) * t
        const lat = a.lat + (b.lat - a.lat) * t
        // 经纬度 → 屏幕方向：纬度向上、经度向右；角度自正北顺时针
        const dLng = (b.lng - a.lng) * Math.cos((lat * Math.PI) / 180)
        const dLat = b.lat - a.lat
        const angle = (Math.atan2(dLng, dLat) * 180) / Math.PI
        arrows.push({ point: { lng, lat }, angle })
        break
      }
      target -= segs[i]
    }
  })
  return arrows
}

// ==================== 播放（地图 marker / SVG 示意图共用同一进度） ====================
const progress = ref(0)
const playing = ref(false)
let playTimer: number | undefined

/** 进度 p(0~100) → 各线路上的当前位置（按分段长度线性插值） */
const positionsAt = (p: number) => {
  const ratio = Math.max(0, Math.min(100, p)) / 100
  return visibleRoutes.value.map((route) => {
    const pts = route.points
    if (pts.length === 0) return { key: route.key, index: 0, point: null }
    if (pts.length === 1) return { key: route.key, index: 0, point: pts[0] }
    const segs: number[] = []
    let total = 0
    for (let i = 1; i < pts.length; i++) {
      const d = Math.hypot(pts[i].lng - pts[i - 1].lng, pts[i].lat - pts[i - 1].lat)
      segs.push(d)
      total += d
    }
    let target = total * ratio
    for (let i = 0; i < segs.length; i++) {
      if (target <= segs[i] || i === segs.length - 1) {
        const t = segs[i] === 0 ? 0 : target / segs[i]
        return {
          key: route.key,
          index: i,
          point: {
            lng: pts[i].lng + (pts[i + 1].lng - pts[i].lng) * t,
            lat: pts[i].lat + (pts[i + 1].lat - pts[i].lat) * t
          }
        }
      }
      target -= segs[i]
    }
    return { key: route.key, index: 0, point: pts[0] }
  })
}

/** SVG 示意图：真实经纬度 → 归一化到 1000×620 画布 */
const svgProjection = computed(() => {
  const pts = visibleRoutes.value.flatMap((r) => r.points)
  if (!pts.length) return null
  const lngs = pts.map((p) => p.lng)
  const lats = pts.map((p) => p.lat)
  const minLng = Math.min(...lngs)
  const maxLng = Math.max(...lngs)
  const minLat = Math.min(...lats)
  const maxLat = Math.max(...lats)
  const spanLng = maxLng - minLng || 0.001
  const spanLat = maxLat - minLat || 0.001
  return {
    project: (p: { lng: number; lat: number }) => ({
      x: 60 + ((p.lng - minLng) / spanLng) * 880,
      // 纬度越大越靠北 → y 越小
      y: 40 + ((maxLat - p.lat) / spanLat) * 520
    })
  }
})

const svgLines = computed(() => {
  const proj = svgProjection.value
  if (!proj) return []
  return visibleRoutes.value.map((route) => {
    const dots = route.points.map((p) => proj.project(p))
    return { key: route.key, color: route.color, dots, points: dots.map((d) => `${d.x},${d.y}`).join(' ') }
  })
})

const svgMovers = computed(() => {
  const proj = svgProjection.value
  if (!proj) return []
  return positionsAt(progress.value)
    .filter((p) => p.point)
    .map((p) => {
      const pos = proj.project(p.point!)
      const route = visibleRoutes.value.find((r) => r.key === p.key)
      return { key: p.key, x: pos.x, y: pos.y, color: route?.color || '#409eff' }
    })
})

const applyProgress = (p: number) => {
  if (mapReady.value && map) {
    const BMapGL = window.BMapGL
    positionsAt(p).forEach((pos) => {
      const entry = moverMarkers.value[pos.key]
      if (entry && pos.point) {
        const bd = gcj02ToBd09(pos.point.lng, pos.point.lat)
        entry.marker.setPosition(new BMapGL.Point(bd.lng, bd.lat))
      }
    })
  }
}

const onSeek = () => applyProgress(progress.value)

const stopPlay = () => {
  playing.value = false
  if (playTimer) {
    window.clearInterval(playTimer)
    playTimer = undefined
  }
}

const togglePlay = () => {
  if (playing.value) {
    stopPlay()
    return
  }
  playing.value = true
  if (progress.value >= 100) progress.value = 0
  playTimer = window.setInterval(() => {
    progress.value = Math.min(100, progress.value + 2)
    applyProgress(progress.value)
    if (progress.value >= 100) stopPlay()
  }, 120)
}

const redraw = () => {
  progress.value = 0
  if (mapReady.value) {
    drawMap()
    applyProgress(0)
  }
}

/** 打开时加载方案明细与站点/车辆基础数据，再画图 */
const load = async () => {
  if (!props.planIds.length) return
  loading.value = true
  stopPlay()
  try {
    const [stationList, vehicleList, driverList] = await Promise.all([
      StationApi.getSimpleStationList().catch(() => []),
      VehicleApi.getSimpleVehicleList().catch(() => []),
      DriverApi.getSimpleDriverList().catch(() => [])
    ])
    stations.value = stationList
    vehicles.value = vehicleList
    drivers.value = driverList
    plans.value = await Promise.all(props.planIds.map((id) => DispatchApi.getDispatchPlan(id)))
    // 真实道路轨迹：一次请求拿到"每车每段"的道路几何（后端带缓存，高德不可用时段落 provider=EUCLIDEAN）
    const roadmaps = await Promise.all(
      props.planIds.map((id) => DispatchApi.getDispatchPlanRoadmap(id).catch(() => null))
    )
    const segmentMap = new Map<string, { provider: string; points: { lng: number; lat: number }[] }>()
    roadmaps.forEach((roadmap) => {
      ;(roadmap?.segments ?? []).forEach((segment) => {
        const points = (segment.points ?? [])
          .filter((p) => p.longitude != null && p.latitude != null)
          .map((p) => ({ lng: Number(p.longitude), lat: Number(p.latitude) }))
        if (points.length < 2) return
        segmentMap.set(`${roadmap?.planId}:${segment.vehicleId ?? 0}:${segment.visitSequence ?? 0}`, {
          provider: segment.provider || 'EUCLIDEAN',
          points
        })
      })
    })
    roadmapSegments.value = segmentMap
    activePlanId.value = 0
    buildRoutes()
  } finally {
    loading.value = false
  }
  await nextTick()
  await initMap()
  redraw()
}

watch(
  () => props.modelValue,
  (v) => {
    if (v) load()
    else stopPlay()
  }
)
// 已在打开状态下切换到另一套方案（如列表里再点一次「可视化」）也要重新加载
watch(
  () => props.planIds,
  () => {
    if (props.modelValue) load()
  }
)

onBeforeUnmount(stopPlay)
</script>

<style lang="scss" scoped>
.viz-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.viz-summary {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  b {
    color: var(--el-color-primary);
    font-size: 15px;
  }
}
.viz-body {
  display: flex;
  gap: 12px;
  align-items: stretch;
}
.viz-map-wrap {
  position: relative;
  flex: 1 1 58%;
  min-width: 420px;
  height: 520px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
.viz-map {
  width: 100%;
  height: 100%;
}
.viz-svg {
  width: 100%;
  height: 100%;
}
.viz-svg-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  background: #f7f9fc;
}
.viz-map-note {
  position: absolute;
  left: 10px;
  top: 10px;
  z-index: 4;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: rgba(255, 255, 255, 0.85);
  padding: 2px 8px;
  border-radius: 4px;
}
.viz-legend {
  position: absolute;
  right: 10px;
  top: 10px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #123f6e;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #c7d8ea;
  border-radius: 6px;
  padding: 3px 8px;
}
.viz-legend .legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}
.viz-legend .legend-line {
  width: 18px;
  height: 0;
  border-top: 3px solid #1f5e9e;
  display: inline-block;
}
.viz-legend .legend-line.est {
  border-top-style: dashed;
  border-top-color: #f0a020;
}
.viz-legend .legend-arrow {
  color: #1f5e9e;
  font-size: 13px;
}
.viz-legend .legend-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: #1f5e9e;
  color: #fff;
  font-size: 10px;
}
.viz-playbar {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 10px;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(255, 255, 255, 0.92);
  border-radius: 6px;
  padding: 6px 10px;
}
.viz-slider {
  flex: 1;
}
.viz-progress {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  width: 34px;
  text-align: right;
}
.viz-timeline {
  flex: 1 1 42%;
  max-height: 520px;
  overflow-y: auto;
  padding-right: 4px;
}
.route-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
}
.route-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.route-color {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}
.route-meta {
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.trace-real {
  color: #1f5e9e;
  font-weight: 600;
}
.trace-est {
  color: #e6a23c;
  font-weight: 600;
}
.stop-row {
  display: grid;
  grid-template-columns: 20px 10px 56px 1fr auto auto;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 0;
}
.stop-seq {
  color: var(--el-text-color-placeholder);
  text-align: right;
}
.stop-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.stop-act {
  font-weight: 600;
}
.stop-station {
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stop-order {
  color: var(--el-color-primary);
  font-family: monospace;
}
.stop-time {
  color: var(--el-text-color-secondary);
}
// 同一套动作类名：圆点用背景色，文字用同色字色
.stop-dot.act-depart,
.stop-dot.act-return {
  background: var(--el-color-info);
}
.stop-dot.act-pickup {
  background: var(--el-color-warning);
}
.stop-dot.act-deliver {
  background: var(--el-color-success);
}
.stop-dot.act-seat {
  background: var(--el-color-primary);
}
.stop-act.act-depart,
.stop-act.act-return {
  color: var(--el-color-info);
}
.stop-act.act-pickup {
  color: var(--el-color-warning);
}
.stop-act.act-deliver {
  color: var(--el-color-success);
}
.stop-act.act-seat {
  color: var(--el-color-primary);
}
</style>
