<template>
  <div class="bigscreen-viewport">
    <div class="bigscreen-canvas" :style="canvasStyle">
      <!-- ① 顶栏 -->
      <header class="bs-header">
        <div class="bs-header__side bs-header__side--left">
          <span class="bs-dot" :class="connected ? 'is-ok' : 'is-warn'"></span>
          <span class="bs-header__sub">{{ connected ? '数据链路正常' : '数据延迟中' }}</span>
        </div>
        <h1 class="bs-header__title">山乡客货邮 · 智慧运营大屏</h1>
        <div class="bs-header__side bs-header__side--right">
          <span class="bs-header__updated">最后更新 {{ lastUpdatedText }}</span>
          <span class="bs-header__clock">{{ clockText }}</span>
          <button class="bs-fullscreen-btn" type="button" @click="toggleFullscreen">
            {{ isFullscreen ? '退出全屏' : '全屏' }}
          </button>
        </div>
      </header>

      <!-- 主体三栏 -->
      <main class="bs-body">
        <!-- ② 左栏 -->
        <aside class="bs-col bs-col--left">
          <section class="bs-panel">
            <h2 class="bs-panel__title">运营 KPI</h2>
            <div class="bs-kpi-grid">
              <div v-for="kpi in kpiList" :key="kpi.label" class="bs-kpi">
                <div class="bs-kpi__value" :class="kpi.gold ? 'is-gold' : ''">
                  {{ kpi.value ?? '--' }}
                </div>
                <div class="bs-kpi__label">{{ kpi.label }}</div>
              </div>
            </div>
          </section>

          <section class="bs-panel bs-panel--grow">
            <h2 class="bs-panel__title">
              订单与营收 · 24 小时
              <span v-if="trendStale" class="bs-stale">数据延迟 {{ staleMinutes(trendStale) }} 分钟</span>
            </h2>
            <div ref="trendChartRef" class="bs-chart bs-chart--trend"></div>
          </section>

          <section class="bs-panel bs-panel--grow">
            <h2 class="bs-panel__title">
              订单类型分布
              <span v-if="typeStale" class="bs-stale">数据延迟 {{ staleMinutes(typeStale) }} 分钟</span>
            </h2>
            <div ref="typeChartRef" class="bs-chart bs-chart--type"></div>
          </section>
        </aside>

        <!-- ③ 中栏 -->
        <section class="bs-col bs-col--center">
          <div class="bs-map-wrap">
            <div ref="mapRef" class="bs-map"></div>
            <!-- SEP-05：优先使用百度 GL 官方暗色样式；SDK 不支持 setMapStyleV2 时由该遮罩兜底 -->
            <div v-if="shadeEnabled" class="bs-map__shade"></div>
            <div v-if="mapError" class="bs-map__hint">{{ mapError }}</div>
          </div>
          <div class="bs-map-kpis">
            <div class="bs-map-kpi">
              <span class="bs-map-kpi__num">{{ centerKpi.inTransit }}</span>
              <span class="bs-map-kpi__label">在途车辆</span>
            </div>
            <div class="bs-map-kpi">
              <span class="bs-map-kpi__num">{{ centerKpi.idle }}</span>
              <span class="bs-map-kpi__label">空闲车辆</span>
            </div>
            <div class="bs-map-kpi">
              <span class="bs-map-kpi__num">{{ centerKpi.disabled }}</span>
              <span class="bs-map-kpi__label">停用</span>
            </div>
            <div class="bs-map-kpi">
              <span class="bs-map-kpi__num">{{ centerKpi.completionRate }}</span>
              <span class="bs-map-kpi__label">班次完成率</span>
            </div>
          </div>
        </section>

        <!-- ④ 右栏 -->
        <aside class="bs-col bs-col--right">
          <section class="bs-panel bs-panel--grow">
            <h2 class="bs-panel__title">
              今日班次执行
              <span v-if="shiftStale" class="bs-stale">数据延迟 {{ staleMinutes(shiftStale) }} 分钟</span>
            </h2>
            <ul class="bs-shift-list">
              <li v-if="!shiftList.length" class="bs-empty">暂无启用班次</li>
              <li v-for="shift in shiftList.slice(0, 8)" :key="shift.shiftId" class="bs-shift">
                <span class="bs-shift__time">{{ formatTime(shift.plannedDepartureTime) }}</span>
                <span class="bs-shift__name">{{ shift.shiftCode }} {{ shift.routeName || '' }}</span>
                <span class="bs-shift__status" :class="shiftStatusClass(shift.status)">
                  {{ shiftStatusText(shift.status) }}
                </span>
              </li>
            </ul>
          </section>

          <section class="bs-panel">
            <h2 class="bs-panel__title">
              返程结算（今日）
              <span v-if="overview?.settlement == null && !overviewStale" class="bs-stale">数据延迟中</span>
            </h2>
            <template v-if="overview?.settlement">
              <div class="bs-settle">
                <div class="bs-settle__item">
                  <span class="bs-settle__num">{{ overview.settlement.totalDistance ?? 0 }}</span>
                  <span class="bs-settle__label">总里程 km</span>
                </div>
                <div class="bs-settle__item">
                  <span class="bs-settle__num">{{ overview.settlement.passengerCount ?? 0 }}</span>
                  <span class="bs-settle__label">服务乘客</span>
                </div>
                <div class="bs-settle__item">
                  <span class="bs-settle__num">{{ overview.settlement.parcelCount ?? 0 }}</span>
                  <span class="bs-settle__label">包裹收发</span>
                </div>
              </div>
            </template>
            <div v-else class="bs-empty">结算数据暂不可用</div>
            <div class="bs-driver-rate">
              <span>司机参与率（近似：今日有执行记录司机 / 司机总数）</span>
              <strong>{{ driverParticipation }}</strong>
            </div>
          </section>

          <section class="bs-panel bs-panel--grow">
            <h2 class="bs-panel__title">
              异常与待办
              <!-- SEP-06：当前无实时告警表，一期以"交接争议 + 证件到期"组合替代，不伪造告警 -->
              <span class="bs-panel__tag">一期口径：交接争议 + 证件到期</span>
            </h2>
            <ul class="bs-alert-list">
              <li v-if="!alertList.length" class="bs-empty">暂无异常待办</li>
              <li v-for="(alert, idx) in alertList.slice(0, 6)" :key="idx" class="bs-alert">
                <span class="bs-alert__dot"></span>
                <span class="bs-alert__text">{{ alert.text }}</span>
                <span class="bs-alert__time">{{ alert.time }}</span>
              </li>
            </ul>
            <div class="bs-todo-note">
              <span class="bs-todo-note__item" title="无告警表无接口">实时告警流 · 建设中</span>
              <span class="bs-todo-note__item" title="transport_algorithm_request 无耗时字段且无读接口">算法 QPS/P99 · 建设中</span>
              <span class="bs-todo-note__item" title="无跨车聚合接口（单车单次上限 2000 点）">GPS 热力 · 建设中</span>
              <span class="bs-todo-note__item" title="仅调度校验时返回入参相关">站点作业热力 · 建设中</span>
            </div>
          </section>
        </aside>
      </main>

      <!-- ⑤ 底栏 -->
      <footer class="bs-footer">
        <span class="bs-footer__label">最新动态</span>
        <div class="bs-footer__events">
          <span v-if="!eventList.length" class="bs-empty">暂无事件</span>
          <span v-for="(ev, idx) in eventList.slice(0, 5)" :key="idx" class="bs-footer__event">
            [{{ ev.time }}] {{ ev.text }}
          </span>
        </div>
        <span class="bs-footer__version">v1.0 · 山乡夜航</span>
      </footer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, computed } from 'vue'
import echarts from '@/plugins/echarts'
import { loadBaiduMapSdk, gcj02ToBd09, clusterByGrid, shouldCluster, clusterBubbleStyle, BIGSCREEN_MAP_STYLE } from '@/components/Map/src/utils'
import {
  getBigScreenOverview,
  getBigScreenMapData,
  getBigScreenVehicles,
  type BigScreenOverviewVO,
  type BigScreenShiftVO
} from '@/api/transport/bigscreen'
import { getDriverExpiringList, type DriverVO } from '@/api/transport/driver'
import { getHandoverPage } from '@/api/transport/handover'
import type { MonitoringMapDataVO, MonitoringVehicleVO } from '@/api/transport/monitoring'

defineOptions({ name: 'TransportBigScreen' })

// ============ 刷新分层（SEP-03） ============
// T1 静态 5min：map-data ｜ T2 中频 60s：overview（后端缓存 48s）｜ T3 高频 15s：vehicles（后端缓存 12s）
// 异常与待办 10min。规范：禁止无限循环 CSS 动画（7×24 运行），时钟/轮换均为低频定时器。
const T1_MS = 5 * 60 * 1000
const T2_MS = 60 * 1000
const T3_MS = 15 * 1000
const ALERT_MS = 10 * 60 * 1000

// ============ 画布缩放：1920×1080 设计稿等比 ============
const DESIGN_W = 1920
const DESIGN_H = 1080
const scale = ref(1)
const canvasStyle = computed(() => ({
  width: `${DESIGN_W}px`,
  height: `${DESIGN_H}px`,
  transform: `scale(${scale.value})`,
  transformOrigin: '0 0'
}))
const rescale = () => {
  scale.value = Math.min(window.innerWidth / DESIGN_W, window.innerHeight / DESIGN_H)
}

// ============ 状态 ============
const overview = ref<BigScreenOverviewVO | null>(null)
const vehicles = ref<MonitoringVehicleVO[]>([])
const mapError = ref('')
/** SEP-05：SDK 不支持 setMapStyleV2 时回退到半透明遮罩（默认开启，成功设置样式后关闭） */
const shadeEnabled = ref(true)
const connected = computed(() => !overviewStale.value && !vehicleStale.value)
const lastUpdatedText = ref('--:--:--')
const clockText = ref('')

// 各模块"最近一次成功时间"——失败时显示"数据延迟 xm"（SEP-03 规范）
const overviewStale = ref(0)
const vehicleStale = ref(0)
const mapStale = ref(0)
const alertStale = ref(0)
const trendStale = computed(() => overviewStale.value)
const typeStale = computed(() => overviewStale.value)
const shiftStale = computed(() => overviewStale.value)

const staleMinutes = (since: number) => (since ? Math.max(1, Math.round((Date.now() - since) / 60000)) : 0)

// ============ KPI ============
const kpiList = computed(() => [
  { label: '订单总量', value: overview.value?.orderTotal },
  { label: '今日订单', value: overview.value?.orderToday },
  { label: '营收总额(元)', value: overview.value?.orderAmountTotal, gold: true },
  { label: '今日营收(元)', value: overview.value?.orderAmountToday, gold: true },
  { label: '站点数', value: overview.value?.stationCount },
  { label: '司机数', value: overview.value?.driverTotal }
])

const centerKpi = computed(() => ({
  inTransit: vehicles.value.filter((v) => v.status === 1).length,
  idle: vehicles.value.filter((v) => v.status === 0).length,
  disabled: vehicles.value.filter((v) => v.status === 2).length,
  completionRate: completionRateText.value
}))

const completionRateText = computed(() => {
  const s = overview.value?.shiftSummary
  if (!s || !s.total) return '--%'
  return `${Math.round((s.completed / s.total) * 100)}%`
})

// SEP-06：司机在线率无真实字段，用"今日有执行记录的司机/司机总数"近似，UI 明示口径
const driverParticipation = computed(() => {
  const shifts = overview.value?.shiftExecution ?? []
  const activeDrivers = new Set(
    shifts.filter((s) => s.status !== 0 && s.driverId != null).map((s) => s.driverId)
  )
  const total = overview.value?.driverTotal ?? 0
  if (!total) return '--'
  return `${activeDrivers.size}/${total}`
})

// ============ 班次列表 ============
const shiftList = computed<BigScreenShiftVO[]>(() => (overview.value?.shiftExecution ?? []) as BigScreenShiftVO[])
const formatTime = (time?: string) => (time ? time.slice(0, 5) : '--:--')
const shiftStatusText = (status: number) => (status === 0 ? '未发车' : status === 1 ? '在途' : '已完成')
const shiftStatusClass = (status: number) =>
  ({ 0: 'is-pending', 1: 'is-transit', 2: 'is-done' })[status] ?? ''

// ============ 异常与待办（SEP-06：交接争议 + 证件到期，无告警表不伪造） ============
interface ScreenAlert {
  text: string
  time: string
}
const alertList = ref<ScreenAlert[]>([])

const loadAlerts = async () => {
  try {
    const list: ScreenAlert[] = []
    // 交接争议（status=2）
    const handovers = await getHandoverPage({ pageNo: 1, pageSize: 20, status: 2 })
    for (const h of handovers.list ?? []) {
      list.push({
        text: `交接争议：${h.orderNo ?? '订单'} @ ${h.stationName ?? '-'}（${h.fromDriverName ?? '-'} → ${h.toDriverName ?? '-'}）`,
        time: (h.handoverTime ?? h.createTime ?? '').slice(5, 16)
      })
    }
    // 证件到期（30 天内）
    const expiring: DriverVO[] = await getDriverExpiringList(30)
    for (const d of expiring ?? []) {
      list.push({
        text: `证件到期：司机 ${d.name ?? d.id} 驾照/保险 30 天内到期`,
        time: ''
      })
    }
    alertList.value = list
    alertStale.value = 0
  } catch {
    if (!alertStale.value) alertStale.value = Date.now()
  }
}

// ============ 底栏事件流（如实：今日班次动态 + 最近交接，不做伪造） ============
const eventList = computed<ScreenAlert[]>(() => {
  const events: ScreenAlert[] = []
  for (const s of shiftList.value) {
    if (s.status === 1) {
      events.push({ text: `${s.shiftCode} ${s.routeName ?? ''} 在途 · 司机 ${s.driverName ?? '-'}`, time: formatTime(s.departTime) })
    } else if (s.status === 2) {
      events.push({ text: `${s.shiftCode} ${s.routeName ?? ''} 已完成`, time: formatTime(s.arriveTime) })
    }
  }
  return events.sort((a, b) => (a.time > b.time ? 1 : -1)).reverse()
})

// ============ 图表（cargo-dark 主题，SEP-04 注册后免逐图配色） ============
const trendChartRef = ref<HTMLDivElement>()
const typeChartRef = ref<HTMLDivElement>()
let trendChart: ReturnType<typeof echarts.init> | null = null
let typeChart: ReturnType<typeof echarts.init> | null = null
const TYPE_NAMES: Record<number, string> = {
  1: '客运',
  2: '货运',
  3: '客货混'
}

const renderTrend = () => {
  const data = overview.value?.hourlyTrend ?? []
  trendChart?.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单量', '营收(元)'], right: 8, top: 0, itemWidth: 12, itemHeight: 8 },
    xAxis: { type: 'category', data: data.map((d) => d.date.slice(11, 16)), boundaryGap: true },
    yAxis: [
      { type: 'value', name: '单' },
      { type: 'value', name: '元', splitLine: { show: false } }
    ],
    series: [
      { name: '订单量', type: 'bar', data: data.map((d) => d.count), barMaxWidth: 10, itemStyle: { borderRadius: [2, 2, 0, 0] } },
      { name: '营收(元)', type: 'line', yAxisIndex: 1, data: data.map((d) => d.amount), smooth: true, symbol: 'none' }
    ]
  })
}

const renderType = () => {
  const data = (overview.value?.typeDistribution ?? []).map((d) => ({
    name: TYPE_NAMES[d.type ?? 0] ?? `类型${d.type ?? '?'}`,
    value: d.count
  }))
  typeChart?.setOption({
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', right: 4, top: 'middle', itemWidth: 12, itemHeight: 8 },
    series: [
      {
        type: 'pie',
        radius: ['48%', '72%'],
        center: ['38%', '52%'],
        label: { show: false },
        data
      }
    ]
  })
}

const initCharts = async () => {
  await nextTick()
  if (trendChartRef.value) trendChart = echarts.init(trendChartRef.value, 'cargo-dark')
  if (typeChartRef.value) typeChart = echarts.init(typeChartRef.value, 'cargo-dark')
  renderTrend()
  renderType()
}

// ============ 地图（T1 层） ============
const mapRef = ref<HTMLDivElement>()
let map: any = null
let routeOverlays: any[] = []
let vehicleMarkers = new Map<number, any>()
/** SEP-05：车辆聚合气泡覆盖物 */
let clusterBubbles: any[] = []

const initMap = async () => {
  try {
    await loadBaiduMapSdk(15000)
    const BMapGL = (window as any).BMapGL
    if (!mapRef.value || !BMapGL) return
    map = new BMapGL.Map(mapRef.value, { enableMapClick: false })
    map.centerAndZoom(new BMapGL.Point(107.5, 26.6), 11) // 默认中心（黔南山区），后续由站点集合自适应
    map.enableScrollWheelZoom(false)
    // SEP-05：优先用百度 GL 官方暗色样式（无需控制台配置）；不被支持时保留 CSS 遮罩兜底
    try {
      map.setMapStyleV2?.(BIGSCREEN_MAP_STYLE)
      if (map.setMapStyleV2) shadeEnabled.value = false
    } catch {
      shadeEnabled.value = true
    }
    await loadMapData()
    await paintVehicles()
  } catch {
    mapError.value = '地图加载失败：请检查百度地图 AK Referer 白名单或网络后刷新'
  }
}

const loadMapData = async () => {
  try {
    const data: MonitoringMapDataVO = await getBigScreenMapData()
    const BMapGL = (window as any).BMapGL
    if (!map || !BMapGL) return
    routeOverlays.forEach((o) => map.removeOverlay(o))
    routeOverlays = []
    const bounds = new BMapGL.Bounds()
    let hasPoint = false
    // 线路：真实道路折线优先，否则虚线示意（与监控页同口径）
    for (const route of data.routes ?? []) {
      const pts: any[] = []
      const line = route.roadPoints?.length
        ? route.roadPoints.map((p) => gcj02ToBd09(p.longitude, p.latitude))
        : route.points.map((p) => gcj02ToBd09(p.longitude, p.latitude))
      for (const bd of line) {
        pts.push(new BMapGL.Point(bd.lng, bd.lat))
        bounds.extend(pts[pts.length - 1])
        hasPoint = true
      }
      if (pts.length >= 2) {
        const polyline = new BMapGL.Polyline(pts, {
          strokeColor: 'var(--screen-primary)',
          strokeWeight: route.roadPoints?.length ? 3 : 2,
          strokeOpacity: route.roadPoints?.length ? 0.85 : 0.5,
          strokeStyle: route.roadPoints?.length ? 'solid' : 'dashed'
        })
        map.addOverlay(polyline)
        routeOverlays.push(polyline)
      }
    }
    // 站点
    for (const s of data.stations ?? []) {
      if (s.longitude == null || s.latitude == null) continue
      const bd = gcj02ToBd09(s.longitude, s.latitude)
      const point = new BMapGL.Point(bd.lng, bd.lat)
      bounds.extend(point)
      hasPoint = true
      const marker = new BMapGL.Marker(point)
      map.addOverlay(marker)
      routeOverlays.push(marker)
      const label = new BMapGL.Label(s.stationName, {
        offset: new BMapGL.Size(8, -8),
        enableMassClear: false
      })
      label.setStyle({
        color: 'var(--screen-text)',
        backgroundColor: 'rgba(17,27,43,0.85)',
        border: '1px solid var(--screen-line)',
        borderRadius: '4px',
        fontSize: '11px',
        padding: '1px 5px'
      })
      map.addOverlay(label)
      routeOverlays.push(label)
    }
    if (hasPoint) map.setViewport(bounds)
    mapError.value = ''
    mapStale.value = 0
  } catch {
    if (!mapStale.value) mapStale.value = Date.now()
  }
}

const paintVehicles = async () => {
  try {
    const list: MonitoringVehicleVO[] = await getBigScreenVehicles()
    vehicles.value = list
    vehicleStale.value = 0
    lastUpdatedText.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    const BMapGL = (window as any).BMapGL
    if (!map || !BMapGL) return
    // 全量重建（车辆数少；超过阈值时改走网格聚合，避免 T3 15s 刷新时叠加数百覆盖物）
    vehicleMarkers.forEach((m) => map.removeOverlay(m))
    vehicleMarkers = new Map()
    clusterBubbles.forEach((b) => map.removeOverlay(b))
    clusterBubbles = []
    const visible = list.filter((v) => v.status === 1 && v.longitude != null && v.latitude != null)
    if (shouldCluster(visible.length)) {
      const points = visible.map((v) => {
        const bd = gcj02ToBd09(v.longitude!, v.latitude!)
        return { lng: bd.lng, lat: bd.lat, vehicle: v }
      })
      clusterByGrid(points, (p) => {
        const px = map.pointToPixel(new BMapGL.Point(p.lng, p.lat))
        return { x: px.x, y: px.y }
      }).forEach((bucket) => {
        const point = new BMapGL.Point(bucket.lng, bucket.lat)
        if (bucket.items.length === 1) {
          const marker = new BMapGL.Marker(point, { title: bucket.items[0].vehicle.plateNo })
          const icon = marker.getIcon()
          icon.setImageSize?.(new BMapGL.Size(18, 26))
          map.addOverlay(marker)
          vehicleMarkers.set(bucket.items[0].vehicle.vehicleId, marker)
          return
        }
        const bubble = new BMapGL.Label(String(bucket.items.length), {
          position: point,
          offset: new BMapGL.Size(-18, -18)
        })
        bubble.setStyle(clusterBubbleStyle(bucket.items.length))
        map.addOverlay(bubble)
        clusterBubbles.push(bubble)
      })
      return
    }
    for (const v of visible) {
      const vBd = gcj02ToBd09(v.longitude!, v.latitude!)
      const point = new BMapGL.Point(vBd.lng, vBd.lat)
      const marker = new BMapGL.Marker(point, { title: v.plateNo })
      const icon = marker.getIcon()
      icon.setImageSize?.(new BMapGL.Size(18, 26))
      map.addOverlay(marker)
      vehicleMarkers.set(v.vehicleId, marker)
    }
  } catch {
    if (!vehicleStale.value) vehicleStale.value = Date.now()
  }
}

const paintOverview = async () => {
  try {
    const data = await getBigScreenOverview()
    overview.value = data
    overviewStale.value = 0
    renderTrend()
    renderType()
  } catch {
    if (!overviewStale.value) overviewStale.value = Date.now()
  }
}

// ============ 全屏 ============
const isFullscreen = ref(false)
const toggleFullscreen = () => {
  const el = document.documentElement
  if (!document.fullscreenElement) {
    el.requestFullscreen?.()
    isFullscreen.value = true
  } else {
    document.exitFullscreen?.()
    isFullscreen.value = false
  }
}

// ============ 时钟 ============
let clockTimer: number | undefined
const tickClock = () => {
  const now = new Date()
  clockText.value = now.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    weekday: 'short'
  }) + ' ' + now.toLocaleTimeString('zh-CN', { hour12: false })
}

// ============ 生命周期 ============
let t1Timer: number | undefined
let t2Timer: number | undefined
let t3Timer: number | undefined
let alertTimer: number | undefined

onMounted(() => {
  rescale()
  window.addEventListener('resize', rescale)
  tickClock()
  clockTimer = window.setInterval(tickClock, 1000)
  initCharts()
  initMap()
  paintOverview()
  loadAlerts()
  t1Timer = window.setInterval(loadMapData, T1_MS)
  t2Timer = window.setInterval(paintOverview, T2_MS)
  t3Timer = window.setInterval(paintVehicles, T3_MS)
  alertTimer = window.setInterval(loadAlerts, ALERT_MS)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', rescale)
  ;[clockTimer, t1Timer, t2Timer, t3Timer, alertTimer].forEach((t) => t && window.clearInterval(t))
  trendChart?.dispose()
  typeChart?.dispose()
  map?.destroy?.()
})
</script>

<style scoped>
/* ============ 山乡夜航 · 山泉蓝调（SEP-03 配色规范，拒绝 DataV 霓虹青紫） ============ */
.bigscreen-viewport {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: var(--screen-bg);
  overflow: hidden;
}
.bigscreen-canvas {
  position: absolute;
  top: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  background: var(--screen-bg);
  color: var(--screen-text);
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ① 顶栏 */
.bs-header {
  height: 88px;
  flex: 0 0 88px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  background: linear-gradient(180deg, var(--screen-panel) 0%, rgba(17, 27, 43, 0) 100%);
  border-bottom: 1px solid var(--screen-line);
}
.bs-header__title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 6px;
  color: var(--screen-text);
  text-shadow: 0 0 24px rgba(79, 147, 214, 0.28);
}
.bs-header__side {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 440px;
  font-size: 14px;
  color: var(--screen-text-sub);
}
.bs-header__side--right {
  justify-content: flex-end;
}
.bs-header__clock {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
  color: var(--screen-text);
}
.bs-header__sub,
.bs-header__updated {
  white-space: nowrap;
}
.bs-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.bs-dot.is-ok {
  background: var(--screen-success);
}
.bs-dot.is-warn {
  background: var(--screen-clay);
}
.bs-fullscreen-btn {
  background: transparent;
  border: 1px solid var(--screen-line);
  color: var(--screen-text-sub);
  border-radius: 6px;
  padding: 4px 12px;
  cursor: pointer;
  font-size: 13px;
}
.bs-fullscreen-btn:hover {
  border-color: var(--screen-primary);
  color: var(--screen-primary);
}

/* 主体三栏 */
.bs-body {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px 24px;
  min-height: 0;
}
.bs-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}
.bs-col--left,
.bs-col--right {
  width: 440px;
  flex: 0 0 440px;
}
.bs-col--center {
  flex: 1;
  min-width: 0;
}

/* 面板 */
.bs-panel {
  position: relative;
  background: linear-gradient(180deg, var(--screen-panel-top) 0%, var(--screen-panel) 100%);
  border: 1px solid var(--screen-line);
  border-radius: 16px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.bs-panel--grow {
  flex: 1;
}
.bs-panel::before {
  content: '';
  position: absolute;
  left: 0;
  top: 16px;
  bottom: 16px;
  width: 3px;
  border-radius: 2px;
  background: var(--screen-primary);
}
.bs-panel__title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 600;
  color: var(--screen-text);
  display: flex;
  align-items: center;
  gap: 10px;
}
.bs-panel__tag {
  font-size: 11px;
  font-weight: 400;
  color: var(--screen-text-sub);
  border: 1px solid var(--screen-line);
  border-radius: 4px;
  padding: 1px 6px;
}

/* KPI 网格 */
.bs-kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.bs-kpi__value {
  font-size: 40px;
  font-weight: 700;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
  color: var(--screen-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-kpi__value.is-gold {
  color: var(--screen-gold);
}
.bs-kpi__label {
  margin-top: 2px;
  font-size: 13px;
  color: var(--screen-text-sub);
}

/* 图表容器 */
.bs-chart {
  flex: 1;
  min-height: 0;
}
.bs-chart--trend,
.bs-chart--type {
  min-height: 150px;
}

/* 数据延迟标注 */
.bs-stale {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--screen-clay);
}
.bs-stale::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--screen-clay);
}

/* 中栏地图 */
.bs-map-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--screen-line);
  border-radius: 16px;
  overflow: hidden;
}
.bs-map {
  width: 100%;
  height: 100%;
}
.bs-map__shade {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: rgba(11, 18, 32, 0.55);
}
.bs-map__hint {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--screen-text-sub);
  font-size: 14px;
  background: var(--screen-panel);
}
.bs-map-kpis {
  flex: 0 0 72px;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-top: 16px;
}
.bs-map-kpi {
  background: var(--screen-panel);
  border: 1px solid var(--screen-line);
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}
.bs-map-kpi__num {
  font-size: 28px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--screen-primary);
}
.bs-map-kpi__label {
  font-size: 12px;
  color: var(--screen-text-sub);
}

/* 班次列表 */
.bs-shift-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: hidden;
  flex: 1;
}
.bs-shift {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
  border-bottom: 1px solid var(--screen-line);
  font-size: 13px;
}
.bs-shift:last-child {
  border-bottom: none;
}
.bs-shift__time {
  font-variant-numeric: tabular-nums;
  color: var(--screen-text-sub);
  flex: 0 0 44px;
}
.bs-shift__name {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-shift__status {
  flex: 0 0 auto;
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
}
.bs-shift__status.is-pending {
  color: var(--screen-text-sub);
  border: 1px solid var(--screen-grid);
}
.bs-shift__status.is-transit {
  color: var(--screen-primary);
  border: 1px solid rgba(79, 147, 214, 0.4);
}
.bs-shift__status.is-done {
  color: var(--screen-success);
  border: 1px solid rgba(76, 175, 80, 0.4);
}

/* 结算 */
.bs-settle {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 12px;
}
.bs-settle__num {
  display: block;
  font-size: 26px;
  font-weight: 700;
  color: var(--screen-gold);
  font-variant-numeric: tabular-nums;
}
.bs-settle__label {
  font-size: 12px;
  color: var(--screen-text-sub);
}
.bs-driver-rate {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--screen-text-sub);
  border-top: 1px dashed var(--screen-grid);
  padding-top: 10px;
}
.bs-driver-rate strong {
  color: var(--screen-text);
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

/* 异常与待办 */
.bs-alert-list {
  list-style: none;
  margin: 0;
  padding: 0;
  flex: 1;
  overflow: hidden;
}
.bs-alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
}
.bs-alert__dot {
  flex: 0 0 6px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--screen-clay);
}
.bs-alert__text {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.bs-alert__time {
  flex: 0 0 auto;
  color: var(--screen-text-sub);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.bs-todo-note {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--screen-grid);
}
.bs-todo-note__item {
  font-size: 11px;
  color: var(--screen-offline);
  border: 1px dashed var(--screen-grid);
  border-radius: 4px;
  padding: 2px 8px;
}

/* ⑤ 底栏 */
.bs-footer {
  flex: 0 0 56px;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 32px;
  border-top: 1px solid var(--screen-line);
  background: var(--screen-panel);
  font-size: 13px;
}
.bs-footer__label {
  color: var(--screen-primary);
  font-weight: 600;
  flex: 0 0 auto;
}
.bs-footer__events {
  flex: 1;
  display: flex;
  gap: 24px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.bs-footer__event {
  color: var(--screen-text-sub);
}
.bs-footer__version {
  color: var(--screen-offline);
  flex: 0 0 auto;
}

.bs-empty {
  color: var(--screen-offline);
  font-size: 13px;
  padding: 8px 0;
}
</style>
