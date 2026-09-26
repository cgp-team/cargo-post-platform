<template>
  <div class="bigscreen-viewport">
    <div class="bigscreen-canvas" :style="canvasStyle">
      <!-- ① 顶栏 -->
      <header class="bs-header">
        <div class="bs-header__side bs-header__side--left">
          <span class="bs-dot" :class="connected ? 'is-ok' : 'is-warn'"></span>
          <span class="bs-header__sub">{{ connected ? '数据链路正常' : '数据延迟中' }}</span>
        </div>
        <h1 class="bs-header__title">
          山乡客货邮 · 智慧运营大屏
          <span class="bs-header__title-en">SMART OPERATIONS · CHONGQING</span>
        </h1>
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
            <h2 class="bs-panel__title">
              运营 KPI
              <span v-if="selectedDistrict" class="bs-scope">{{ selectedDistrict }}</span>
            </h2>
            <div class="bs-kpi-grid">
              <div v-for="kpi in kpiList" :key="kpi.label" class="bs-kpi">
                <div class="bs-kpi__value" :class="kpi.gold ? 'is-gold' : ''">
                  {{ kpi.value ?? '--' }}
                </div>
                <div class="bs-kpi__label">
                  {{ kpi.label }}
                  <span v-if="kpi.global && selectedDistrict" class="bs-scope bs-scope--global">全局</span>
                </div>
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
            <!-- 划片区快捷条：全部区县可横滚；也可直接点地图任意区域 -->
            <div v-if="districtsReady" class="bs-district-bar">
              <span class="bs-district-bar__tag">片区</span>
              <div ref="chipBarRef" class="bs-district-bar__scroll">
                <button
                  class="bs-chip"
                  type="button"
                  :class="{ 'is-active': !selectedDistrict }"
                  @click="selectDistrict(null)"
                >
                  全域
                </button>
                <button
                  v-for="d in districtChips"
                  :key="d"
                  class="bs-chip"
                  type="button"
                  :class="{ 'is-active': selectedDistrict === d }"
                  @click="selectDistrict(d)"
                >
                  {{ d }}
                </button>
              </div>
            </div>
            <!-- 底图形态切换：矢量 / 卫星 -->
            <div class="bs-map-type">
              <button
                type="button"
                :class="{ 'is-active': mapType === 'vector' }"
                @click="setMapType('vector')"
              >
                矢量
              </button>
              <button
                type="button"
                :class="{ 'is-active': mapType === 'satellite' }"
                @click="setMapType('satellite')"
              >
                卫星
              </button>
            </div>
            <div v-if="districtsReady" class="bs-map__tip">点击地图区域可切换片区</div>
            <!-- 车辆信息卡（点车辆 marker 打开） -->
            <div v-if="selectedVehicle" class="bs-vehicle-card">
              <button class="bs-vehicle-card__close" type="button" @click="selectedVehicleId = null">×</button>
              <div class="bs-vehicle-card__plate">{{ selectedVehicle.plateNo }}</div>
              <div class="bs-vehicle-card__row">
                <span>司机</span>
                <strong>{{ selectedVehicle.driverName || '-' }}</strong>
              </div>
              <div class="bs-vehicle-card__row">
                <span>线路</span>
                <strong>{{ selectedVehicle.routeName || '-' }}</strong>
              </div>
              <div class="bs-vehicle-card__row">
                <span>班次</span>
                <strong>{{ selectedVehicle.shiftCode || '-' }}</strong>
              </div>
              <div class="bs-vehicle-card__row">
                <span>速度</span>
                <strong class="num">{{ selectedVehicle.speedKmh ?? '-' }} km/h</strong>
              </div>
              <div class="bs-vehicle-card__row">
                <span>状态</span>
                <strong>{{ vehicleStatusText(selectedVehicle.status) }}</strong>
              </div>
              <div class="bs-vehicle-card__foot">
                {{ selectedVehicle.dataSource === 'REAL_STALE' ? '位置已过期' : '实时位置' }}<span
                  v-if="selectedVehicle.lastLocationTime"
                >
                  · {{ selectedVehicle.lastLocationTime.replace('T', ' ').slice(5, 16) }}</span
                >
              </div>
            </div>
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
              <span v-if="selectedDistrict" class="bs-scope bs-scope--global">全局</span>
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
              <span v-if="selectedDistrict" class="bs-scope bs-scope--global">全局</span>
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
import { nextTick, onBeforeUnmount, onMounted, ref, computed, watch } from 'vue'
import echarts from '@/plugins/echarts'
import {
  loadBaiduMapSdk,
  gcj02ToBd09,
  clusterByGrid,
  shouldCluster,
  clusterBubbleStyle,
  BIGSCREEN_MAP_STYLE
} from '@/components/Map/src/utils'
import {
  getBigScreenOverview,
  getBigScreenMapData,
  getBigScreenVehicles,
  getBigScreenDistricts,
  type BigScreenOverviewVO,
  type BigScreenShiftVO,
  type BigScreenDistrictsVO
} from '@/api/transport/bigscreen'
import { getDriverExpiringList, type DriverVO } from '@/api/transport/driver'
import { getHandoverPage } from '@/api/transport/handover'
import type { MonitoringMapDataVO, MonitoringVehicleVO } from '@/api/transport/monitoring'

defineOptions({ name: 'TransportBigScreen' })

// ============ 提速：SDK 与数据全并行（原串行为 SDK→数据→渲染） ============
// 大屏 chunk 一加载就发起百度 SDK 下载；地图就绪与数据到达谁先都行，到齐即渲染。
const sdkReady: Promise<void | null> = loadBaiduMapSdk(20000).catch(() => null)

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

// ============ 划片区（区县，GCJ-02 多边形来自后端 OSM 资源） ============
const districts = ref<BigScreenDistrictsVO['districts']>([])
const districtsReady = computed(() => districts.value.length > 0)
/** 功能区（两江新区等）与真实区县多边形重叠：判定优先真实行政区（与后端同口径） */
const ZONE_NAMES = new Set(['两江新区', '重庆高新区', '万盛经济技术开发区'])
/** null = 全域口径 */
const selectedDistrict = ref<string | null>(null)
/** 打开信息卡的车辆（null 关闭） */
const selectedVehicleId = ref<number | null>(null)
const selectedVehicle = computed(
  () => vehicles.value.find((v) => v.vehicleId === selectedVehicleId.value) ?? null
)

// 各模块"最近一次成功时间"——失败时显示"数据延迟 xm"（SEP-03 规范）
const overviewStale = ref(0)
const vehicleStale = ref(0)
const mapStale = ref(0)
const alertStale = ref(0)
const trendStale = computed(() => overviewStale.value)
const typeStale = computed(() => overviewStale.value)
const shiftStale = computed(() => overviewStale.value)

const staleMinutes = (since: number) => (since ? Math.max(1, Math.round((Date.now() - since) / 60000)) : 0)

// ============ 区县几何（前端点包含判断，坐标系 GCJ-02，与站点/车辆同源） ============
const pointInRings = (lng: number, lat: number, rings: number[][][]): boolean => {
  const outer = rings[0]
  if (!outer) return false
  const inRing = (ring: number[][]): boolean => {
    let inside = false
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const [xi, yi] = ring[i]
      const [xj, yj] = ring[j]
      if ((yi > lat) !== (yj > lat) && lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside
    }
    return inside
  }
  if (!inRing(outer)) return false
  for (let h = 1; h < rings.length; h++) {
    if (inRing(rings[h])) return false
  }
  return true
}

/** 是否落在当前选中片区内（未选中=全域，全部通过；GCJ 空间供站点/车辆过滤，地图点击判定另走 BD 空间 districtOfBd） */
const inSelectedDistrict = (lng?: number | null, lat?: number | null): boolean => {
  if (!selectedDistrict.value) return true
  if (lng == null || lat == null) return false
  return districts.value.some(
    (d) => d.name === selectedDistrict.value && pointInRings(lng, lat, d.rings)
  )
}

// ============ KPI ============
const kpiList = computed(() => [
  { label: '订单总量', value: overview.value?.orderTotal },
  { label: '今日订单', value: overview.value?.orderToday },
  { label: '营收总额(元)', value: overview.value?.orderAmountTotal, gold: true },
  { label: '今日营收(元)', value: overview.value?.orderAmountToday, gold: true },
  { label: '站点数', value: overview.value?.stationCount },
  { label: '司机数', value: overview.value?.driverTotal, global: true }
])

// 划片区口径：车辆三态按当前片区统计（司机数/班次等无属地维度保持全局，UI 标注）
const vehiclesInDistrict = computed(() =>
  vehicles.value.filter((v) => inSelectedDistrict(v.longitude, v.latitude))
)
const centerKpi = computed(() => ({
  inTransit: vehiclesInDistrict.value.filter((v) => v.status === 1).length,
  idle: vehiclesInDistrict.value.filter((v) => v.status === 0).length,
  disabled: vehiclesInDistrict.value.filter((v) => v.status === 2).length,
  completionRate: completionRateText.value
}))

/** 快捷片区条：全部区县（判定不依赖底图标注，点条目=精确选中），横向滚动 */
const districtChips = computed(() => {
  const seen = new Set<string>()
  const list: string[] = []
  for (const d of districts.value) {
    if (!seen.has(d.name)) {
      seen.add(d.name)
      list.push(d.name)
    }
  }
  if (selectedDistrict.value && !list.includes(selectedDistrict.value)) {
    list.unshift(selectedDistrict.value)
  }
  return list
})
const chipBarRef = ref<HTMLDivElement>()
watch(selectedDistrict, async () => {
  if (!selectedDistrict.value) return
  await nextTick()
  chipBarRef.value
    ?.querySelector('.bs-chip.is-active')
    ?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' })
})

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

// ============ 地图（T1 层 · 提速：SDK/数据并行，到齐即渲染） ============
const mapRef = ref<HTMLDivElement>()
let map: any = null
let mapReady = false
let routeOverlays: any[] = []
let districtOverlays: any[] = []
let vehicleMarkers = new Map<number, any>()
let vehicleLabels = new Map<number, any>()
/** SEP-05：车辆聚合气泡覆盖物 */
let clusterBubbles: any[] = []
/** 数据缓存：与 SDK 下载并行拉取，先到先存，地图就绪后立即渲染 */
let cachedMapData: MonitoringMapDataVO | null = null
let cachedVehicles: MonitoringVehicleVO[] | null = null
/** 区县边界的 BD-09 缓存：点击判定/绘制/质心统一走 BD 空间，与覆盖物命中完全一致（消除转换偏差） */
let districtsBd: { name: string; rings: number[][][] }[] = []
/** 底图形态：矢量 / 卫星 */
const mapType = ref<'vector' | 'satellite'>('vector')
/** 边界 polygon 点击时间戳：与 map click 双触发时忽略后者（见 initMap） */
let lastOverlayClickAt = 0
/** 车辆位置补间：15s 轮询差值平滑移动（真数据，不造假轨迹） */
const tweens = new Map<number, { marker: any; label: any; from: any; to: any; start: number }>()
let tweenRaf = 0
const TWEEN_MS = 1600

// 重庆默认视图（主城中心）。原实现中心写死黔南山区(107.5,26.6)，站点接口无数据时地图停在贵州
const CQ_CENTER = { lng: 106.5511, lat: 29.563, zoom: 9 }
/** 重庆辖区包围盒（GCJ-02 近似），防止异常站点把视野拉出重庆 */
const CQ_BOUNDS = { minLng: 105.2, maxLng: 110.3, minLat: 28.1, maxLat: 32.3 }

const vehicleStatusText = (status: number) =>
  ({ 0: '空闲', 1: '在途', 2: '停用' })[status] ?? '未知'

const initMap = async () => {
  try {
    await sdkReady
    const BMapGL = (window as any).BMapGL
    if (!mapRef.value || !BMapGL) return
    // 启用地图点击：点任意区域命中区县即切换片区
    map = new BMapGL.Map(mapRef.value, { enableMapClick: true })
    map.centerAndZoom(new BMapGL.Point(CQ_CENTER.lng, CQ_CENTER.lat), CQ_CENTER.zoom)
    map.enableScrollWheelZoom(false)
    // SEP-05：优先用百度 GL 官方暗色样式（无需控制台配置）；不被支持时保留 CSS 遮罩兜底
    try {
      map.setMapStyleV2?.(BIGSCREEN_MAP_STYLE)
      if (map.setMapStyleV2) shadeEnabled.value = false
    } catch {
      shadeEnabled.value = true
    }
    // 边界 polygon 的 click 可能同时触发 map click，400ms 内忽略后者避免“选中又被取消”
    map.addEventListener('click', (e: any) => {
      if (Date.now() - lastOverlayClickAt < 400) return
      const x = e.latlng.lng
      const y = e.latlng.lat
      // BD 空间判定（与覆盖物命中完全一致）；落界外（江面/缝隙）时 10px 吸附最近边界
      const name = districtOfBd(x, y) ?? (districtsBd.length ? snapDistrictBd(x, y) : null)
      if (name) selectDistrict(name)
    })
    mapReady = true
    if (districts.value.length) renderDistrictOverlays()
    if (cachedMapData) renderMapData()
    if (cachedVehicles) renderVehicles()
  } catch {
    mapError.value = '地图加载失败：请检查百度地图 AK Referer 白名单或网络后刷新'
  }
}

const fetchMapData = async () => {
  try {
    cachedMapData = await getBigScreenMapData()
    mapStale.value = 0
    if (mapReady) renderMapData()
  } catch {
    if (!mapStale.value) mapStale.value = Date.now()
  }
}

/** 站点/线路视野（限重庆范围内）；划片区时视野由 selectDistrict 负责 */
const fitWithinChongqing = (bounds: any) => {
  const sw = bounds.getSouthWest()
  const ne = bounds.getNorthEast()
  const cx = (sw.lng + ne.lng) / 2
  const cy = (sw.lat + ne.lat) / 2
  if (cx >= CQ_BOUNDS.minLng && cx <= CQ_BOUNDS.maxLng && cy >= CQ_BOUNDS.minLat && cy <= CQ_BOUNDS.maxLat) {
    map.setViewport(bounds)
  }
}

const renderMapData = () => {
  const data = cachedMapData
  const BMapGL = (window as any).BMapGL
  if (!map || !BMapGL || !data) return
  routeOverlays.forEach((o) => map.removeOverlay(o))
  routeOverlays = []
  const bounds = new BMapGL.Bounds()
  let hasPoint = false
  // 线路：真实道路折线优先，否则虚线示意（与监控页同口径）；划片区时仅保留途经该片区的线路
  for (const route of data.routes ?? []) {
    const rawPoints = route.roadPoints?.length ? route.roadPoints : route.points
    if (selectedDistrict.value && !rawPoints.some((p) => inSelectedDistrict(p.longitude, p.latitude))) {
      continue
    }
    const pts: any[] = rawPoints.map((p) => {
      const bd = gcj02ToBd09(p.longitude, p.latitude)
      const pt = new BMapGL.Point(bd.lng, bd.lat)
      bounds.extend(pt)
      hasPoint = true
      return pt
    })
    if (pts.length >= 2) {
      const polyline = new BMapGL.Polyline(pts, {
        // canvas 覆盖物不认 CSS 变量，必须字面量色值（SEP-04 同口径）
        strokeColor: '#4F93D6',
        strokeWeight: route.roadPoints?.length ? 3 : 2,
        strokeOpacity: route.roadPoints?.length ? 0.85 : 0.5,
        strokeStyle: route.roadPoints?.length ? 'solid' : 'dashed'
      })
      map.addOverlay(polyline)
      routeOverlays.push(polyline)
    }
  }
  // 站点（划片区时仅显示区内站点）
  for (const s of data.stations ?? []) {
    if (s.longitude == null || s.latitude == null) continue
    if (!inSelectedDistrict(s.longitude, s.latitude)) continue
    const bd = gcj02ToBd09(s.longitude, s.latitude)
    const point = new BMapGL.Point(bd.lng, bd.lat)
    bounds.extend(point)
    hasPoint = true
    const marker = new BMapGL.Marker(point)
    map.addOverlay(marker)
    routeOverlays.push(marker)
    const label = new BMapGL.Label(s.stationName, {
      position: point,
      offset: new BMapGL.Size(8, -8),
      enableMassClear: false
    })
    label.setStyle({
      color: '#DCE8F5',
      backgroundColor: 'rgba(13,22,36,0.9)',
      border: '1px solid rgba(79,147,214,0.35)',
      borderRadius: '4px',
      fontSize: '11px',
      padding: '1px 5px'
    })
    map.addOverlay(label)
    routeOverlays.push(label)
  }
  if (hasPoint && !selectedDistrict.value) fitWithinChongqing(bounds)
  mapError.value = ''
  mapStale.value = 0
}

// ============ 区县边界图层（划片区） ============
/** GCJ → BD 一次转换缓存：点击判定/绘制/质心/吸附统一走 BD 空间，与覆盖物命中几何完全一致 */
const buildBdCache = () => {
  districtsBd = districts.value.map((d) => ({
    name: d.name,
    rings: d.rings.map((ring) =>
      ring.map(([lng, lat]) => {
        const bd = gcj02ToBd09(lng, lat)
        return [bd.lng, bd.lat]
      })
    )
  }))
}

/** 射线法（BD 空间） */
const inRingBd = (ring: number[][], x: number, y: number): boolean => {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i]
    const [xj, yj] = ring[j]
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
  }
  return inside
}

/** BD 点所在区县（districtsBd 顺序 = 真实行政区优先） */
const districtOfBd = (x: number, y: number): string | null => {
  for (const d of districtsBd) {
    if (!inRingBd(d.rings[0], x, y)) continue
    let hole = false
    for (let h = 1; h < d.rings.length; h++) {
      if (inRingBd(d.rings[h], x, y)) {
        hole = true
        break
      }
    }
    if (!hole) return d.name
  }
  return null
}

/** 吸附容错：点在所有区县之外（江面/边界缝隙）时，命中 10px 内最近边界所属区县 */
const snapDistrictBd = (x: number, y: number): string | null => {
  const BMapGL = (window as any).BMapGL
  if (!map || !BMapGL) return null
  const px = map.pointToPixel(new BMapGL.Point(x, y))
  const TH = 10
  let best: string | null = null
  let bestD = TH
  for (const d of districtsBd) {
    for (const ring of d.rings) {
      for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
        const a = map.pointToPixel(new BMapGL.Point(ring[j][0], ring[j][1]))
        const b = map.pointToPixel(new BMapGL.Point(ring[i][0], ring[i][1]))
        const dx = b.x - a.x
        const dy = b.y - a.y
        const len2 = dx * dx + dy * dy
        let t = len2 ? ((px.x - a.x) * dx + (px.y - a.y) * dy) / len2 : 0
        t = Math.max(0, Math.min(1, t))
        const dist = Math.hypot(px.x - (a.x + t * dx), px.y - (a.y + t * dy))
        if (dist < bestD) {
          bestD = dist
          best = d.name
        }
      }
    }
  }
  return best
}

/** 面积加权质心（shoelace，BD 空间） */
const centroidBd = (ring: number[][]): [number, number] => {
  let a = 0
  let cx = 0
  let cy = 0
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [x0, y0] = ring[j]
    const [x1, y1] = ring[i]
    const f = x0 * y1 - x1 * y0
    a += f
    cx += (x0 + x1) * f
    cy += (y0 + y1) * f
  }
  a *= 0.5
  if (Math.abs(a) < 1e-12) return [ring[0][0], ring[0][1]]
  return [cx / (6 * a), cy / (6 * a)]
}

/** 区名标签锚点：同名多块中取包围盒最大的一块（飞地场景主块在主城） */
const labelCentroidOf = (name: string): [number, number] => {
  let bestRing: number[][] | null = null
  let bestArea = -1
  for (const d of districtsBd) {
    if (d.name !== name) continue
    const ring = d.rings[0]
    let minx = Infinity
    let miny = Infinity
    let maxx = -Infinity
    let maxy = -Infinity
    for (const [x, y] of ring) {
      if (x < minx) minx = x
      if (y < miny) miny = y
      if (x > maxx) maxx = x
      if (y > maxy) maxy = y
    }
    const area = (maxx - minx) * (maxy - miny)
    if (area > bestArea) {
      bestArea = area
      bestRing = ring
    }
  }
  return bestRing ? centroidBd(bestRing) : [0, 0]
}

const districtBoundsOf = (name: string): any => {
  const BMapGL = (window as any).BMapGL
  const bounds = new BMapGL.Bounds()
  for (const d of districtsBd) {
    if (d.name !== name) continue
    for (const ring of d.rings) {
      for (const [lng, lat] of ring) {
        bounds.extend(new BMapGL.Point(lng, lat))
      }
    }
  }
  return bounds
}

/** 重庆全域视野（回「全域」时用） */
const chongqingBounds = (): any => {
  const BMapGL = (window as any).BMapGL
  const bounds = new BMapGL.Bounds()
  for (const d of districtsBd) {
    for (const ring of d.rings) {
      for (const [lng, lat] of ring) {
        bounds.extend(new BMapGL.Point(lng, lat))
      }
    }
  }
  return bounds
}

const renderDistrictOverlays = () => {
  const BMapGL = (window as any).BMapGL
  if (!map || !BMapGL || !districtsBd.length) return
  districtOverlays.forEach((o) => {
    try {
      map.removeOverlay(o)
    } catch {
      /* 单个移除失败不阻断 */
    }
  })
  districtOverlays = []
  const selected = selectedDistrict.value
  // 反向遍历：功能区下层、真实行政区上层；选中某区时其余区县隐藏（只看这个区）
  for (const d of [...districtsBd].reverse()) {
    if (selected && d.name !== selected) continue
    const isSel = d.name === selected
    for (const ring of d.rings) {
      if (ring.length < 3) continue
      try {
        const pts = ring.map(([lng, lat]) => new BMapGL.Point(lng, lat))
        // 平面多边形始终绘制（保底可见）
        const polygon = new BMapGL.Polygon(pts, {
          strokeColor: isSel ? '#8CC0F5' : '#5B93CF',
          strokeWeight: isSel ? 2 : 1.5,
          strokeOpacity: isSel ? 0.95 : 0.75,
          fillColor: isSel ? '#2E6BB0' : '#14283F',
          fillOpacity: isSel ? 0.5 : 0.06,
          enableMassClear: false
        })
        polygon.addEventListener('click', () => {
          lastOverlayClickAt = Date.now()
          selectDistrict(d.name)
        })
        map.addOverlay(polygon)
        districtOverlays.push(polygon)
        // 选中区县叠加 3D 棱柱（增强展示；构造失败仅降级，不影响保底多边形）
        if (isSel && typeof BMapGL.Prism === 'function') {
          try {
            const prism = new BMapGL.Prism(pts, 600, {
              strokeColor: '#8CC0F5',
              strokeWeight: 2,
              strokeOpacity: 0.95,
              fillColor: '#2E6BB0',
              fillOpacity: 0.5
            })
            prism.addEventListener('click', () => {
              lastOverlayClickAt = Date.now()
              selectDistrict(d.name)
            })
            map.addOverlay(prism)
            districtOverlays.push(prism)
          } catch (e) {
            console.warn('[bigscreen] Prism 构造失败，已回退平面多边形', e)
          }
        }
      } catch (e) {
        console.warn('[bigscreen] 区县多边形渲染失败', d.name, e)
      }
    }
  }
  // 区名标签：每个 name 一个（锚在最大块质心），可点击；选中时只保留该区
  const seen = new Set<string>()
  for (const d of districtsBd) {
    if (seen.has(d.name)) continue
    seen.add(d.name)
    if (selected && d.name !== selected) continue
    try {
      const isSel = d.name === selected
      const [cx, cy] = labelCentroidOf(d.name)
      const label = new BMapGL.Label(d.name, {
        position: new BMapGL.Point(cx, cy),
        offset: new BMapGL.Size(-22, -11),
        enableMassClear: false
      })
      label.setStyle(
        isSel
          ? {
              color: '#F5D98A',
              backgroundColor: 'rgba(13,22,36,0.92)',
              border: '1px solid #F0C566',
              borderRadius: '3px',
              fontSize: '13px',
              fontWeight: '600',
              letterSpacing: '2px',
              padding: '2px 10px'
            }
          : {
              color: '#A8C0DC',
              backgroundColor: 'rgba(13,22,36,0.62)',
              border: '1px solid rgba(79,147,214,0.3)',
              borderRadius: '3px',
              fontSize: '11px',
              letterSpacing: '1px',
              padding: '1px 7px'
            }
      )
      label.addEventListener('click', () => {
        lastOverlayClickAt = Date.now()
        selectDistrict(d.name)
      })
      map.addOverlay(label)
      districtOverlays.push(label)
    } catch (e) {
      console.warn('[bigscreen] 区名标签渲染失败', d.name, e)
    }
  }
}

/** 选中片区（再次点击同一片区/点「全域」回全局口径） */
const selectDistrict = (name: string | null) => {
  const next = selectedDistrict.value === name ? null : name
  if (selectedDistrict.value === next) return
  selectedDistrict.value = next
  selectedVehicleId.value = null
  console.info('[bigscreen] 片区切换 →', next ?? '全域', '| 区县数据条目:', districtsBd.length)
  // ① 先取景 + 俯仰（即使后续渲染异常，「只看这个区」的镜头效果也必须生效）
  const fitTarget = () => {
    if (!mapReady) return
    if (next) {
      const bounds = districtBoundsOf(next)
      try {
        map.setViewport(bounds, { margins: [70, 70, 70, 70] })
      } catch {
        try {
          map.setViewport(bounds)
        } catch (e) {
          console.warn('[bigscreen] 片区取景失败', e)
        }
      }
    } else {
      try {
        map.setViewport(chongqingBounds())
      } catch (e) {
        console.warn('[bigscreen] 全域取景失败', e)
      }
    }
  }
  try {
    map.setTilt?.(next ? 45 : 0)
  } catch {
    /* SDK 不支持俯仰则保持平面 */
  }
  fitTarget()
  // 俯仰动画会改变可见地表范围，取景补一帧（仍选中同一片区才补）
  if (next) {
    window.setTimeout(() => {
      if (selectedDistrict.value === next) fitTarget()
    }, 450)
  }
  // ② 重建边界（内部已逐元素兜底，整体再兜一层）
  try {
    renderDistrictOverlays()
  } catch (e) {
    console.warn('[bigscreen] 边界渲染失败', e)
  }
  // ③ 数据联动
  if (cachedMapData) renderMapData()
  if (cachedVehicles) renderVehicles()
  paintOverview() // 订单类 KPI 立即切换到新片区口径
}

const loadDistricts = async () => {
  try {
    const data = await getBigScreenDistricts()
    // 真实行政区排前（点包含优先命中）；绘制时反向遍历让功能区在下层、真实区县在上层（点选一致）
    districts.value = [...(data.districts ?? [])].sort(
      (a, b) => Number(ZONE_NAMES.has(a.name)) - Number(ZONE_NAMES.has(b.name))
    )
    buildBdCache()
    console.info('[bigscreen] 区县边界加载完成:', districtsBd.length, '个几何条目')
    if (mapReady) renderDistrictOverlays()
  } catch (e) {
    // 边界不可用时划片区降级（无边界层/无快捷条），其余模块不受影响
    console.warn('[bigscreen] 区县边界加载失败，划片区降级', e)
  }
}

/** 底图形态切换：矢量 ↔ 卫星（卫星下暗色样式自动失效，切回矢量时恢复） */
const setMapType = (type: 'vector' | 'satellite') => {
  if (!map || mapType.value === type) return
  const BMapGL = (window as any).BMapGL
  try {
    if (type === 'satellite') {
      const SAT = (window as any).BMAP_SATELLITE_MAP ?? BMapGL?.BMAP_SATELLITE_MAP
      if (SAT == null) throw new Error('satellite map type unavailable')
      map.setMapType(SAT)
      mapType.value = 'satellite'
    } else {
      const NOR = (window as any).BMAP_NORMAL_MAP ?? BMapGL?.BMAP_NORMAL_MAP
      if (NOR == null) throw new Error('normal map type unavailable')
      map.setMapType(NOR)
      mapType.value = 'vector'
      try {
        map.setMapStyleV2?.(BIGSCREEN_MAP_STYLE)
      } catch {
        /* 保持现状，遮罩兜底逻辑不变 */
      }
    }
  } catch {
    // 常量不可用：按钮维持当前形态，不报错打扰大屏
  }
}

const fetchVehicles = async () => {
  try {
    const list: MonitoringVehicleVO[] = await getBigScreenVehicles()
    cachedVehicles = list
    vehicles.value = list
    vehicleStale.value = 0
    lastUpdatedText.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    if (mapReady) renderVehicles()
  } catch {
    if (!vehicleStale.value) vehicleStale.value = Date.now()
  }
}

/** 3D 风格车辆图标：俯视小货车（车头朝上），data-URI 内联 SVG 零外部依赖，setRotation 控制朝向 */
const TRUCK_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="26" height="26" viewBox="0 0 26 26">
  <ellipse cx="13" cy="22.2" rx="8" ry="2.8" fill="rgba(0,0,0,0.45)"/>
  <rect x="6.5" y="6.2" width="13" height="15.4" rx="3.2" fill="#3D7AB8" stroke="#9CCBF7" stroke-width="1"/>
  <path d="M8.6 7.4h8.8a1.6 1.6 0 0 1 1.6 1.6v3.4H7V9a1.6 1.6 0 0 1 1.6-1.6z" fill="#DCEEFF"/>
  <path d="M11 3.9h4a1.5 1.5 0 0 1 1.5 1.5v1.4H9.5V5.4A1.5 1.5 0 0 1 11 3.9z" fill="#5B93CF" stroke="#9CCBF7" stroke-width="0.8"/>
  <rect x="8.6" y="13.4" width="8.8" height="6.6" rx="1.6" fill="#2E6BB0"/>
  <rect x="9.9" y="14.5" width="6.2" height="1.4" rx="0.7" fill="#7FB6F0" opacity="0.6"/>
  <rect x="5" y="9.2" width="1.9" height="1.4" rx="0.5" fill="#9CCBF7"/>
  <rect x="19.1" y="9.2" width="1.9" height="1.4" rx="0.5" fill="#9CCBF7"/>
</svg>`
const truckIcon = () => {
  const BMapGL = (window as any).BMapGL
  return new BMapGL.Icon(
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(TRUCK_SVG)}`,
    new BMapGL.Size(26, 26),
    { anchor: new BMapGL.Size(13, 13) }
  )
}

/** 车头朝向（0=正北，顺时针） */
const headingDeg = (from: any, to: any) => {
  const dy = to.lat - from.lat
  const dx = (to.lng - from.lng) * Math.cos((((from.lat + to.lat) / 2) * Math.PI) / 180)
  return (Math.atan2(dx, dy) * 180) / Math.PI
}

const stepTweens = () => {
  const now = performance.now()
  const BMapGL = (window as any).BMapGL
  tweens.forEach((t, id) => {
    const k = Math.min(1, (now - t.start) / TWEEN_MS)
    const p = new BMapGL.Point(
      t.from.lng + (t.to.lng - t.from.lng) * k,
      t.from.lat + (t.to.lat - t.from.lat) * k
    )
    t.marker.setPosition(p)
    t.label?.setPosition?.(p)
    if (k >= 1) tweens.delete(id)
  })
  tweenRaf = tweens.size ? requestAnimationFrame(stepTweens) : 0
}

/** 位置补间 + 车头朝向；坐标未变则不动 */
const tweenVehicle = (marker: any, id: number, label: any, to: { lng: number; lat: number }) => {
  const from = marker.getPosition()
  if (!from || (from.lng === to.lng && from.lat === to.lat)) return
  tweens.delete(id)
  marker.setRotation?.(headingDeg(from, to))
  tweens.set(id, { marker, label, from, to, start: performance.now() })
  if (!tweenRaf) tweenRaf = requestAnimationFrame(stepTweens)
}

const clearClusterBubbles = () => {
  clusterBubbles.forEach((b) => map.removeOverlay(b))
  clusterBubbles = []
}

const clearVehicleMarkers = () => {
  tweens.clear()
  vehicleMarkers.forEach((m, id) => {
    map.removeOverlay(m)
    const lb = vehicleLabels.get(id)
    if (lb) map.removeOverlay(lb)
  })
  vehicleMarkers = new Map()
  vehicleLabels = new Map()
}

const renderVehicles = () => {
  try {
    const BMapGL = (window as any).BMapGL
    if (!map || !BMapGL) return
    // 划片区口径：仅区内 + 在途 + 有坐标
    const visible = (cachedVehicles ?? []).filter(
      (v) => v.status === 1 && v.longitude != null && v.latitude != null && inSelectedDistrict(v.longitude, v.latitude)
    )
    if (shouldCluster(visible.length)) {
      // 车辆数大时退回网格聚合（聚合气泡不参与补间动画）
      clearVehicleMarkers()
      clearClusterBubbles()
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
          const marker = new BMapGL.Marker(point, {
            icon: truckIcon(),
            title: `${bucket.items[0].vehicle.plateNo} ${bucket.items[0].vehicle.driverName ?? ''}`
          })
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
    clearClusterBubbles()
    // 增量更新：新建缺失 marker，其余走补间（真数据平滑移动）
    const ids = new Set(visible.map((v) => v.vehicleId))
    ;[...vehicleMarkers.keys()].forEach((id) => {
      if (!ids.has(id)) {
        const m = vehicleMarkers.get(id)
        const lb = vehicleLabels.get(id)
        if (m) map.removeOverlay(m)
        if (lb) map.removeOverlay(lb)
        vehicleMarkers.delete(id)
        vehicleLabels.delete(id)
        tweens.delete(id)
      }
    })
    for (const v of visible) {
      const bd = gcj02ToBd09(v.longitude!, v.latitude!)
      let marker = vehicleMarkers.get(v.vehicleId)
      let label = vehicleLabels.get(v.vehicleId)
      // 聚合模式遗留的无标签 marker：重建以带上车牌标签与点击信息卡
      if (marker && !label) {
        map.removeOverlay(marker)
        vehicleMarkers.delete(v.vehicleId)
        marker = undefined
      }
      if (!marker) {
        const point = new BMapGL.Point(bd.lng, bd.lat)
        marker = new BMapGL.Marker(point, {
          icon: truckIcon(),
          title: `${v.plateNo} ${v.driverName ?? ''}`
        })
        marker.addEventListener('click', () => {
          selectedVehicleId.value = v.vehicleId
        })
        label = new BMapGL.Label(`${v.plateNo}${v.driverName ? ' · ' + v.driverName : ''}`, {
          position: point,
          offset: new BMapGL.Size(-36, -32),
          enableMassClear: false
        })
        label.setStyle({
          color: '#E8F2FD',
          backgroundColor: 'rgba(13,22,36,0.92)',
          border: '1px solid rgba(79,147,214,0.65)',
          borderRadius: '3px',
          fontSize: '10px',
          lineHeight: '14px',
          padding: '0 5px',
          whiteSpace: 'nowrap',
          fontFamily: "'DIN Alternate','PingFang SC',sans-serif"
        })
        map.addOverlay(marker)
        map.addOverlay(label)
        vehicleMarkers.set(v.vehicleId, marker)
        vehicleLabels.set(v.vehicleId, label)
      }
      tweenVehicle(marker, v.vehicleId, label, bd)
    }
  } catch {
    if (!vehicleStale.value) vehicleStale.value = Date.now()
  }
}

const paintOverview = async () => {
  try {
    const data = await getBigScreenOverview(selectedDistrict.value ?? undefined)
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
  // 提速：地图数据/车辆/区县边界/KPI 全部并行发起，与百度 SDK 下载同时进行
  fetchMapData()
  fetchVehicles()
  loadDistricts()
  paintOverview()
  loadAlerts()
  initMap()
  t1Timer = window.setInterval(fetchMapData, T1_MS)
  t2Timer = window.setInterval(paintOverview, T2_MS)
  t3Timer = window.setInterval(fetchVehicles, T3_MS)
  alertTimer = window.setInterval(loadAlerts, ALERT_MS)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', rescale)
  ;[clockTimer, t1Timer, t2Timer, t3Timer, alertTimer].forEach((t) => t && window.clearInterval(t))
  if (tweenRaf) cancelAnimationFrame(tweenRaf)
  tweenRaf = 0
  tweens.clear()
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
  /* 科技感：静态细网格底纹（静态纹理非循环动画，符合 7×24 规范） */
  background-color: var(--screen-bg);
  background-image:
    linear-gradient(rgba(79, 147, 214, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(79, 147, 214, 0.045) 1px, transparent 1px);
  background-size: 48px 48px;
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
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 6px;
  color: var(--screen-text);
  text-shadow: 0 0 24px rgba(79, 147, 214, 0.28);
  text-align: center;
}
.bs-header__title-en {
  display: block;
  font-size: 10px;
  font-weight: 400;
  letter-spacing: 7px;
  color: rgba(79, 147, 214, 0.75);
  margin-top: 2px;
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
/* 科技角标：面板右上切角（::before 已被左侧光条占用） */
.bs-panel::after {
  content: '';
  position: absolute;
  top: -1px;
  right: -1px;
  width: 18px;
  height: 18px;
  border-top: 2px solid rgba(79, 147, 214, 0.75);
  border-right: 2px solid rgba(79, 147, 214, 0.75);
  border-top-right-radius: 16px;
  pointer-events: none;
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
  text-shadow: 0 0 16px rgba(79, 147, 214, 0.45);
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

/* ============ 划片区（区县） ============ */
.bs-scope {
  font-size: 11px;
  font-weight: 400;
  color: #cfe6ff;
  background: rgba(79, 147, 214, 0.18);
  border: 1px solid rgba(79, 147, 214, 0.5);
  border-radius: 3px;
  padding: 1px 7px;
  letter-spacing: 1px;
}
.bs-scope--global {
  color: var(--screen-text-sub);
  background: rgba(255, 255, 255, 0.05);
  border-color: var(--screen-line);
}
.bs-district-bar {
  position: absolute;
  top: 12px;
  left: 14px;
  right: 150px;
  display: flex;
  align-items: center;
  gap: 8px;
  z-index: 6;
  pointer-events: none;
}
.bs-district-bar > * {
  pointer-events: auto;
}
.bs-district-bar__scroll {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  flex: 1;
  min-width: 0;
  padding-bottom: 3px;
  scrollbar-width: thin;
  scrollbar-color: rgba(79, 147, 214, 0.4) transparent;
}
.bs-district-bar__scroll::-webkit-scrollbar {
  height: 4px;
}
.bs-district-bar__scroll::-webkit-scrollbar-thumb {
  background: rgba(79, 147, 214, 0.4);
  border-radius: 2px;
}
.bs-district-bar__scroll::-webkit-scrollbar-track {
  background: transparent;
}
.bs-district-bar__scroll .bs-chip {
  flex: 0 0 auto;
}
.bs-map-type {
  position: absolute;
  top: 12px;
  right: 14px;
  z-index: 7;
  display: flex;
  overflow: hidden;
  background: rgba(13, 22, 36, 0.88);
  border: 1px solid var(--screen-line);
  border-radius: 14px;
}
.bs-map-type button {
  background: none;
  border: none;
  color: var(--screen-text-sub);
  font-size: 12px;
  padding: 4px 13px;
  cursor: pointer;
  letter-spacing: 1px;
}
.bs-map-type button.is-active {
  background: rgba(79, 147, 214, 0.22);
  color: #d7ebff;
}
.bs-district-bar__tag {
  font-size: 12px;
  color: var(--screen-text-sub);
  letter-spacing: 4px;
  padding-right: 8px;
  border-right: 1px solid var(--screen-line);
}
.bs-chip {
  background: rgba(13, 22, 36, 0.88);
  border: 1px solid var(--screen-line);
  color: var(--screen-text-sub);
  font-size: 12px;
  padding: 4px 13px;
  border-radius: 14px;
  cursor: pointer;
  letter-spacing: 1px;
  transition: all 0.2s ease;
}
.bs-chip:hover {
  color: var(--screen-text);
  border-color: rgba(79, 147, 214, 0.5);
}
.bs-chip.is-active {
  background: rgba(79, 147, 214, 0.2);
  border-color: var(--screen-primary);
  color: #d7ebff;
  box-shadow: 0 0 14px rgba(79, 147, 214, 0.4);
}
.bs-map__tip {
  position: absolute;
  right: 12px;
  bottom: 10px;
  z-index: 6;
  font-size: 11px;
  color: var(--screen-text-sub);
  background: rgba(13, 22, 36, 0.78);
  border: 1px dashed rgba(79, 147, 214, 0.4);
  padding: 3px 10px;
  border-radius: 4px;
  pointer-events: none;
}

/* 车辆信息卡 */
.bs-vehicle-card {
  position: absolute;
  top: 56px;
  right: 14px;
  width: 236px;
  background: rgba(11, 18, 32, 0.96);
  border: 1px solid rgba(79, 147, 214, 0.55);
  border-radius: 10px;
  padding: 14px 16px 12px;
  z-index: 7;
  box-shadow: 0 10px 36px rgba(0, 0, 0, 0.5);
}
.bs-vehicle-card::before {
  content: '';
  position: absolute;
  top: -1px;
  left: -1px;
  width: 16px;
  height: 16px;
  border-top: 2px solid var(--screen-gold);
  border-left: 2px solid var(--screen-gold);
  border-top-left-radius: 10px;
}
.bs-vehicle-card__close {
  position: absolute;
  top: 6px;
  right: 9px;
  background: none;
  border: none;
  color: var(--screen-text-sub);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 2px;
}
.bs-vehicle-card__close:hover {
  color: var(--screen-text);
}
.bs-vehicle-card__plate {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--screen-gold);
  margin-bottom: 8px;
  font-variant-numeric: tabular-nums;
}
.bs-vehicle-card__row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  padding: 3px 0;
}
.bs-vehicle-card__row span {
  color: var(--screen-text-sub);
  flex: 0 0 auto;
}
.bs-vehicle-card__row strong {
  color: var(--screen-text);
  font-weight: 500;
  min-width: 0;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bs-vehicle-card__row .num {
  font-variant-numeric: tabular-nums;
  color: #8cc0f5;
}
.bs-vehicle-card__foot {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--screen-line);
  font-size: 11px;
  color: var(--screen-success);
}
</style>
