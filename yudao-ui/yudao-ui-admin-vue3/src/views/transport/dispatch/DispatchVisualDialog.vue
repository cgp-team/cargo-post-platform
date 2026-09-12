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
          <!-- 地图未就绪 / 手动切到示意图 / 站点缺坐标时，用真实坐标画线路示意图覆盖在上层，演示不中断 -->
          <svg
            v-if="!mapReady || showSchematic"
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
          <div v-if="!mapReady || showSchematic" class="viz-map-note">
            {{ showSchematic ? '坐标示意图（按真实经纬度比例绘制）' : (mapError ? '地图不可用，已切换为坐标示意图' : '地图加载中…') }}
          </div>
          <div v-if="!drawableRoutes.length" class="viz-map-empty">
            该方案暂无带经纬度的经停站点，无法绘制路线。请在「站点管理」补齐站点经纬度后重新生成方案。
          </div>
          <!-- 车辆配色图例：演示时一眼看清"哪条线是哪台车" -->
          <div v-if="visibleRoutes.length" class="viz-vehicle-legend">
            <div
              v-for="r in visibleRoutes"
              :key="'lg-' + r.key"
              class="legend-vehicle legend-vehicle-clickable"
              :class="{ picked: selectedVehicleKey === r.key }"
              @click="selectVehicleByKey(r.key)"
            >
              <span class="legend-vehicle-color" :style="{ background: r.color }"></span>
              <span class="legend-vehicle-name">{{ r.title }}</span>
              <span class="legend-vehicle-meta">
                {{ r.orderCount }} 单 · {{ r.legs ? r.legs.length + ' 段' : r.stops.length + ' 站' }}
              </span>
            </div>
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
            <el-button size="small" text @click="toggleSchematic">
              {{ showSchematic ? '切换地图' : '切换示意图' }}
            </el-button>
          </div>
        </div>

        <!-- 右：每车任务段时间线 -->
        <div class="viz-timeline">
          <!-- 两个视角，避免信息堆在一起：按车辆看"怎么走、在哪做什么"；按订单看"整条链路与换乘交接" -->
          <div class="panel-tabs">
            <div class="panel-tab" :class="{ active: panelTab === 'all' }" @click="switchTab('all')">
              总体
            </div>
            <div class="panel-tab" :class="{ active: panelTab === 'vehicle' }" @click="switchTab('vehicle')">
              按车辆（路线 + 操作）
            </div>
            <div class="panel-tab" :class="{ active: panelTab === 'order' }" @click="switchTab('order')">
              按订单（含联运交接）
            </div>
          </div>
          <div class="panel-tip">
            {{ panelTab === 'all' ? '地图显示全部车辆线路'
              : panelTab === 'vehicle' ? '点选下方某台车，地图只显示它的线路'
              : '按司机归堆、按任务段执行顺序排列：从第 1 单依次点下去，地图会一段段连成这名司机这趟任务段的总路线' }}
          </div>

          <!-- 视角一/二：每台车一条线，按行驶顺序列出经停与本站操作 -->
          <template v-if="panelTab !== 'order'">
            <div v-if="!visibleRoutes.length" class="text-gray-400 text-sm">暂无调度明细</div>
            <div
              v-for="route in visibleRoutes"
              :key="route.key"
              class="route-card"
              :class="{ selectable: panelTab === 'vehicle', dimmed: panelTab === 'vehicle' && selectedVehicleKey && selectedVehicleKey !== route.key, picked: selectedVehicleKey === route.key }"
              @click="panelTab === 'vehicle' && selectVehicle(route.key)"
            >
              <div class="route-title">
                <span class="route-color" :style="{ background: route.color }"></span>
                <span class="route-name">{{ route.title }}</span>
              </div>
              <div class="route-sub">
                <template v-if="route.legs">
                  {{ route.legs.length }} 段 · {{ route.distanceText }} km · 订单 {{ route.orderCount }} 单
                  <template v-if="route.windowText"> · 任务窗口 {{ route.windowText }}</template>
                </template>
                <template v-else>
                  {{ route.stops.length }} 站 · {{ route.distanceText }} km · 订单 {{ route.orderCount }} 单
                </template>
                <template v-if="route.driverText"> · {{ route.driverText }}</template>
              </div>
              <!-- 运输段口径（真实车辆）：一段一行，写清从哪到哪、哪单、是真实道路还是直线估算 -->
              <template v-if="route.legs">
                <div v-for="lg in route.legs" :key="'leg' + lg.seq" class="stop-row">
                  <span class="stop-seq">{{ lg.seq }}</span>
                  <span class="stop-dot" :class="lg.estimated ? 'act-seat' : 'act-deliver'"></span>
                  <span class="stop-act" :class="lg.estimated ? 'act-seat' : 'act-deliver'">
                    {{ lg.estimated ? '直线估算' : '真实道路' }}
                  </span>
                  <span class="stop-station">{{ lg.fromName }} → {{ lg.toName }}</span>
                  <span v-if="lg.orderNo" class="stop-order">{{ lg.orderNo }}</span>
                  <span class="stop-time">{{ lg.timeRangeText || lg.timeText }}</span>
                  <span v-if="lg.distanceText && lg.distanceText !== '-'" class="stop-time">{{ lg.distanceText }}km</span>
                  <div v-if="lg.handoverText" class="stop-handover">
                    <span class="stop-handover-icon">🔄</span>
                    <span class="stop-handover-text">{{ lg.handoverText }}</span>
                  </div>
                </div>
              </template>
              <template v-else>
                <div v-for="(s, i) in route.stops" :key="i" class="stop-row">
                  <span class="stop-seq">{{ i + 1 }}</span>
                  <span class="stop-dot" :class="actionClass(s.actionType)"></span>
                  <span class="stop-act" :class="actionClass(s.actionType)">
                    {{ actionLabel(s.actionType) }}{{ quantityText(s) }}
                  </span>
                  <span class="stop-station">{{ s.stationName || stationName(s.stationId) || '-' }}</span>
                  <span v-if="s.orderNo" class="stop-order">{{ s.orderNo }}</span>
                  <span class="stop-time">{{ timeText(s.estimatedArrivalTime) }}</span>
                  <div v-if="handoverInfoFor(s.stationId, s.stationName)" class="stop-handover">
                    <span class="stop-handover-icon">🔄</span>
                    <span class="stop-handover-text">在此转{{ handoverInfoFor(s.stationId, s.stationName) }}</span>
                  </div>
                </div>
              </template>


                          </div>
          </template>

          <!-- 视角二：每张订单的分段路线；不同车辆用地图上同一套颜色，交接点写清交给谁 -->
          <template v-else>
            <div v-if="!linkOrders.length" class="text-gray-400 text-sm">
              本方案暂无订单运输链（或该订单还未生成运输段）
            </div>
            <!-- 按司机归堆：同一司机的订单按任务段执行顺序（第一单 → 最后一单）从上到下排；
                 依次点击这些订单，地图上会连成这名司机这个任务段的总路线（段段相接）。 -->
            <template v-for="g in linkOrderGroups" :key="'group-' + g.key">
              <div class="order-group-head">
                <span class="order-group-dot" :style="{ background: g.color }"></span>
                <span class="order-group-title">{{ g.title }}</span>
                <span class="order-group-meta">任务段 · {{ g.orders.length }} 单（按执行顺序）</span>
              </div>
            <div
              v-for="o in g.orders"
              :key="o.key"
              class="order-card selectable"
              :class="{ dimmed: selectedOrderKey && selectedOrderKey !== o.key, picked: selectedOrderKey === o.key }"
              @click="selectOrder(o.key)"
            >
              <div class="order-head">
                <span class="order-seq">{{ o.seqInDriver }}/{{ o.driverOrderCount }}</span>
                <span class="order-no">订单 {{ o.orderNo }}</span>
                <span class="order-meta">
                  <template v-if="o.startTime">{{ timeText(o.startTime) }}–{{ timeText(o.endTime) }} · </template>
                  {{ o.totalLegs }} 段 · 换乘 {{ o.transferCount }} 次
                  <template v-if="o.durationMinutes"> · 约 {{ o.durationMinutes }} 分钟</template>
                </span>
              </div>
              <div v-if="selectedOrderKey === o.key && hasEstimatedLegs" class="order-est-hint">
                <el-icon><WarningFilled /></el-icon>
                <span>部分路段为直线估算，非真实道路轨迹。地图中虚线段表示估算路线。</span>
              </div>
              <div v-for="(leg, i) in o.legs" :key="i" class="order-leg">
                <span class="leg-color" :style="{ background: leg.color }"></span>
                <span class="leg-seq">{{ i + 1 }}</span>
                <span class="leg-text">
                  <b>{{ leg.plateNo || '车辆' }}</b>{{ leg.driverName ? ` · ${leg.driverName}` : '' }}
                  ：{{ leg.fromStationName || '—' }} → {{ leg.toStationName || '—' }}
                </span>
                <el-tooltip v-if="orderLegEstimates[i]" content="该路段暂无真实道路数据，显示为直线估算" placement="top">
                  <span class="leg-badge est-badge">直线估算</span>
                </el-tooltip>
                <span v-else class="leg-badge real-badge">真实道路</span>
              </div>
              <div v-for="(leg, i) in o.legs" :key="'h' + i">
                <div v-if="leg.handoverTarget" class="order-transfer">
                  ⇄ 在 <b>{{ leg.toStationName }}</b> 交给 {{ leg.handoverTarget }}
                </div>
              </div>
            </div>
            </template>
          </template>
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
import { WarningFilled } from '@element-plus/icons-vue'
import { loadBaiduMapSdk } from '@/components/Map/src/utils'
import * as DispatchApi from '@/api/transport/dispatch'
import * as DriverApi from '@/api/transport/driver'
import * as StationApi from '@/api/transport/station'
import * as VehicleApi from '@/api/transport/vehicle'
import * as TopologyApi from '@/api/transport/topology'

defineOptions({ name: 'DispatchVisualDialog' })

const props = defineProps<{ modelValue: boolean; planIds: number[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v)
})

type RouteStop = DispatchApi.DispatchPlanItemVO
/**
 * 按车辆视角的「一段运输」：来自运输段（transport_leg），是车辆真实执行的任务。
 * 多段联运方案里，算法的「单车经停明细」会把同一片区外的订单也挂在同一台车上，
 * 真实执行口径以运输段为准（哪台车、从哪到哪、交给谁）。
 */
interface VehicleLegRow {
  seq: number
  orderId?: number
  orderNo?: string
  fromName: string
  toName: string
  plateNo?: string
  driverName?: string
  /** true = 该段暂无真实道路轨迹，地图上是直线估算（虚线） */
  estimated: boolean
  handoverText: string
  timeText: string
  /** 该段计划时间区间（离站–到达，写清"几点到几点"）；缺离站时间时退化为"到达 HH:mm" */
  timeRangeText: string
  distanceText: string
}
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
  /** 按车辆视角：该车真实执行的运输段（存在时优先按它展示，而不是算法单车经停明细） */
  legs?: VehicleLegRow[]
  /** 任务时间窗（该方案内该车最早离站 ~ 最晚到达）：一套方案 = 一个任务时间窗 */
  windowText?: string
  /** 归属方案：分组时同一天的多套方案不合并，避免被当成"一台车跑了一整天" */
  /** 该车车牌（运输段分组口径） */
  plateNo?: string
}

const loading = ref(false)
const plans = ref<DispatchApi.DispatchPlanRespVO[]>([])
const activePlanId = ref(0)
const routes = ref<RouteView[]>([])
const stations = ref<StationApi.StationVO[]>([])
const vehicles = ref<VehicleApi.VehicleVO[]>([])
const drivers = ref<DriverApi.DriverVO[]>([])
/** 方案内订单的运输拓扑（多段联运交接展示用；取不到不影响主流程） */
const topologies = ref<TopologyApi.OrderTopologyVO[]>([])

/**
 * 车辆配色：每台车一条线、颜色互不相同，演示时一眼能分清（白+蓝主题下 12 色高对比）。
 * 颜色数量 ≥ 常见车队规模，超出后循环（并在图例里标出）。
 */
const ROUTE_COLORS = [
  '#1F5E9E', // 深蓝（主色）
  '#E6A23C', // 橙
  '#2E9E6B', // 绿
  '#D9534F', // 红
  '#7B5BD6', // 紫
  '#0FA3B1', // 青
  '#D4801A', // 琥珀
  '#C2185B', // 玫红
  '#4A7C1F', // 橄榄绿
  '#5A6ACF', // 靛蓝
  '#8D6E63', // 棕
  '#00838F'  // 深青
]

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

/** 一段运输的计划时间区间文案：09:12–09:35（缺离站时间时退化为"到达 09:35"） */
const timeRangeText = (departure?: string, arrival?: string) => {
  const d = timeText(departure)
  const a = timeText(arrival)
  if (d && a) return `${d}–${a}`
  if (a) return `到达 ${a}`
  return d ? `${d}–` : ''
}

/**
 * 一套方案（一个任务时间窗）的时间范围：该车全部运输段最早离站 ~ 最晚到达。
 * 演示口径：一个任务段时间内完成这批订单，卡片上要写清"这趟几点到几点"。
 */
const windowTextOf = (legs: TopologyApi.TopologyLeg[]) => {
  const departures = legs.map((l) => l.estimatedDeparture).filter(Boolean) as string[]
  const arrivals = legs.map((l) => l.estimatedArrival).filter(Boolean) as string[]
  const start = departures.length ? timeText(departures.reduce((a, b) => (a < b ? a : b))) : ''
  const end = arrivals.length ? timeText(arrivals.reduce((a, b) => (a > b ? a : b))) : ''
  if (start && end) return `${start}–${end}`
  if (end) return `～${end}`
  return start ? `${start}–` : ''
}

/**
 * 本站操作数量文案：**只标货运件数**（揽收/派送 = 件）。
 * 上下车人数由乘客随机到站决定，不是我们能控制的，可视化里不标注，避免误导。
 */
const quantityText = (stop: DispatchApi.DispatchPlanItemVO) => {
  const q = stop.quantity
  if (q == null || q <= 0) return ''
  const action = stop.actionType
  // 上车(1)/下车(2)/发车(0)/返场(5)：不标注人数
  if (action !== 3 && action !== 4) return ''
  return ` ${q}件`
}

const summary = computed(() => {
  const shown = activePlanId.value
    ? plans.value.filter((p) => p.id === activePlanId.value)
    : plans.value
  const orderIds = new Set<string>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.orderNo && orderIds.add(i.orderNo)))
  const vehicleIds = new Set<number>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.vehicleId && vehicleIds.add(i.vehicleId)))
  const distance = shown.reduce((sum, p) => sum + (Number(p.totalDistance) || 0), 0)
  // 车辆数按「实际画出/列出的车辆」统计：多段联运按运输段分组时会包含换乘车辆，
  // 只按算法经停明细统计会少算（明细里换乘车辆不出现）。
  return {
    orderCount: orderIds.size,
    vehicleCount: visibleRoutes.value.length || vehicleIds.size,
    distanceText: distance ? distance.toFixed(1) : '-'
  }
})

/**
 * 实际展示的车辆线路：**优先按运输段（真实车辆）分组**；
 * 方案没有运输段（手工方案/历史数据）时才退回按算法经停明细分组。
 */
const visibleRoutes = computed(() => {
  if (vehicleLegRoutes.value.length) {
    if (!activePlanId.value) return vehicleLegRoutes.value
    const plan = plans.value.find((p) => p.id === activePlanId.value)
    const orderIds = new Set<number>(
      (plan?.items ?? []).map((i) => i.orderId).filter((id): id is number => id != null)
    )
    return buildLegRoutes(orderIds)
  }
  return activePlanId.value ? routes.value.filter((r) => r.planId === activePlanId.value) : routes.value
})

/**
 * 视角：总体 / 按车辆 / 按订单。
 * 切换视角或点选具体车辆/订单时，**右侧列表与地图同步变化**（地图只画当前关注的对象）。
 */
const panelTab = ref<'all' | 'vehicle' | 'order'>('all')
/** 按车辆视角下选中的车辆（route.key）；为空=显示全部 */
const selectedVehicleKey = ref('')
/** 按订单视角下选中的订单（linkOrders.key）；为空=默认显示第一单 */
const selectedOrderKey = ref('')

const switchTab = (tab: 'all' | 'vehicle' | 'order') => {
  panelTab.value = tab
  selectedVehicleKey.value = ''
  selectedOrderKey.value = ''
  redraw()
}

const selectVehicle = (key: string) => {
  selectedVehicleKey.value = selectedVehicleKey.value === key ? '' : key
  redraw()
}

/**
 * 地图/图例点选车辆：切到「按车辆」视角并只显示这一台车（其余车辆线路与标记自动隐藏）。
 * 再次点击同一台车 = 取消选择，恢复显示全部车辆。
 */
const selectVehicleByKey = (key: string) => {
  panelTab.value = 'vehicle'
  selectedVehicleKey.value = selectedVehicleKey.value === key ? '' : key
  redraw()
}

const selectOrder = (key: string) => {
  selectedOrderKey.value = key
  redraw()
}

/** 当前选中订单的分段路线（用运输段的真实道路轨迹画线；不同车辆不同颜色） */
const orderLegRoutes = computed<RouteView[]>(() => {
  const order = linkOrders.value.find((o) => o.key === selectedOrderKey.value) ?? linkOrders.value[0]
  if (!order) return []
  const plan = plans.value.find((p) => (p.items ?? []).some((i) => i.orderId === order.orderId))
  // 每个司机（车辆）一条"累计"路线：从该司机任务段的第一单，一直连到当前点开的这一单。
  // 依次点击同一司机的订单时，地图上的线会一段段接上，形成这名司机这个任务段走的总路线，
  // 而不是每单各画一段互不相连的线（看起来像做完一单瞬移到别处接单）。
  const routes: RouteView[] = []
  const seen = new Set<string>()
  order.legs.forEach((leg) => {
    const plate = leg.plateNo || leg.driverName || '未知车辆'
    if (seen.has(plate)) return
    seen.add(plate)
    const all = rowsByPlate.value.get(plate) ?? []
    // 当前订单的这段在该司机任务段里的位置：只画到"点到的这一单"为止
    const cut = all.findIndex((r) => r.leg.id != null && r.leg.id === leg.id)
    const rows = cut >= 0 ? all.slice(0, cut + 1) : all.filter((r) => r.orderId === order.orderId)
    if (!rows.length) return
    const route = buildDriverSegmentRoute(plate, rows, plan?.id)
    if (route) routes.push(route)
  })
  return routes
})

/** 订单视角中各 leg 是否使用直线估算（key = leg index，value = true 表示估算） */
const orderLegEstimates = computed<Record<number, boolean>>(() => {
  const order = linkOrders.value.find((o) => o.key === selectedOrderKey.value) ?? linkOrders.value[0]
  if (!order) return {}
  const result: Record<number, boolean> = {}
  order.legs.forEach((leg, index) => {
    const key = legKey(leg)
    const cached = legRoadCache.value.get(key)
    const hasNavPoly = (leg.navigationPolyline ?? []).filter((p: any) => p.longitude != null && p.latitude != null).length >= 2
    const hasCachedRoad = cached && cached.length >= 2
    // true = still using straight-line estimate
    result[index] = !hasNavPoly && !hasCachedRoad
  })
  return result
})

/** 订单视角中是否至少有一段使用直线估算 */
const hasEstimatedLegs = computed(() => Object.values(orderLegEstimates.value).some(Boolean))

/** 订单分段道路缓存（key = 起终点坐标）与"正在请求"标记，避免重复请求 */
const legRoadCache = ref<Map<string, { lng: number; lat: number }[]>>(new Map())
const legRoadPending = new Set<string>()
/** Tracks when a leg road request last failed, for retry cooldown */
const legRoadFailed = ref<Map<string, number>>(new Map())
const LEG_ROAD_RETRY_MS = 30_000 // 30s cooldown before retrying a failed leg
const legKey = (leg: TopologyApi.TopologyLeg) =>
  `${leg.fromLongitude},${leg.fromLatitude}->${leg.toLongitude},${leg.toLatitude}`

/** 按需补取真实道路轨迹；失败后进入冷却期，冷却期过后自动重试 */
const ensureLegRoad = async (key: string, fromLng: number, fromLat: number, toLng: number, toLat: number) => {
  if (legRoadCache.value.has(key) || legRoadPending.has(key)) return
  // Respect retry cooldown after a previous failure
  const failedAt = legRoadFailed.value.get(key)
  if (failedAt && Date.now() - failedAt < LEG_ROAD_RETRY_MS) return
  legRoadPending.add(key)
  try {
    const points = await DispatchApi.getRoadBetween({
      fromLongitude: fromLng, fromLatitude: fromLat, toLongitude: toLng, toLatitude: toLat
    })
    if (points && points.length >= 2) {
      const next = new Map(legRoadCache.value)
      next.set(key, points
        .filter((p) => p.longitude != null && p.latitude != null)
        .map((p) => ({ lng: Number(p.longitude), lat: Number(p.latitude) })))
      legRoadCache.value = next
      // Clear failure record on success
      const nextFailed = new Map(legRoadFailed.value)
      nextFailed.delete(key)
      legRoadFailed.value = nextFailed
      redraw()
    } else {
      // API returned empty/insufficient data — record failure for retry
      const nextFailed = new Map(legRoadFailed.value)
      nextFailed.set(key, Date.now())
      legRoadFailed.value = nextFailed
      setTimeout(() => redraw(), LEG_ROAD_RETRY_MS + 1000)
    }
  } catch (e) {
    // Record failure timestamp so computed re-evaluation triggers a retry after cooldown
    const nextFailed = new Map(legRoadFailed.value)
    nextFailed.set(key, Date.now())
    legRoadFailed.value = nextFailed
    // Schedule a redraw after retry cooldown so the next computed evaluation retries
    setTimeout(() => redraw(), LEG_ROAD_RETRY_MS + 1000)
  } finally {
    legRoadPending.delete(key)
  }
}

/** 地图实际绘制的线路（随视角变化）：总体=全部；按车辆=选中车；按订单=选中单的分段 */
const mapRoutes = computed<RouteView[]>(() => {
  if (panelTab.value === 'order') return orderLegRoutes.value
  if (panelTab.value === 'vehicle' && selectedVehicleKey.value) {
    return visibleRoutes.value.filter((r) => r.key === selectedVehicleKey.value)
  }
  return visibleRoutes.value
})

/** 方案内所有订单的运输段（真实车辆执行口径；用于车辆视角分组与配色） */
const topologyLegs = computed(() => {
  const rows: { leg: TopologyApi.TopologyLeg; orderId?: number; orderNo?: string }[] = []
  topologies.value.forEach((t) => {
    ;(t.legs ?? []).forEach((leg) => rows.push({ leg, orderId: t.orderId, orderNo: t.orderNo }))
  })
  return rows
})

/** 当前视角展示的方案内订单号集合（「全部方案」时 = 已加载的全部方案） */
const planOrderIds = computed(() => {
  const shown = activePlanId.value ? plans.value.filter((p) => p.id === activePlanId.value) : plans.value
  const ids = new Set<number>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.orderId && ids.add(i.orderId)))
  return ids
})

/**
 * 同一司机（车辆）在本方案内、按时间排好序的全部承运段 = 该司机这个任务段的执行顺序。
 * 订单视角按司机归堆、依次点击时，用它把段连成整条任务段路线。
 */
const rowsByPlate = computed(() => {
  const map = new Map<string, { leg: TopologyApi.TopologyLeg; orderId?: number; orderNo?: string }[]>()
  topologyLegs.value
    .filter((r) => r.orderId != null && planOrderIds.value.has(r.orderId))
    .forEach((r) => {
      const plate = r.leg.plateNo || r.leg.driverName || '未知车辆'
      if (!map.has(plate)) map.set(plate, [])
      map.get(plate)!.push(r)
    })
  const timeKey = (leg: TopologyApi.TopologyLeg) => leg.estimatedDeparture || leg.estimatedArrival || ''
  map.forEach((rows) =>
    rows.sort(
      (a, b) =>
        timeKey(a.leg).localeCompare(timeKey(b.leg)) || (a.leg.legSequence ?? 0) - (b.leg.legSequence ?? 0)
    )
  )
  return map
})

/**
 * 把一名司机在本任务段内的若干运输段连成一条线：按时间顺序拼接真实道路轨迹。
 * 段与段首尾相接（同一坐标）时去重；不重合说明中间还有空驶/沿骨架运行的连接段，
 * 保留首点让地图把这段也画出来，保证"一段段可以连接上"。
 */
const buildDriverSegmentRoute = (
  plate: string,
  rows: { leg: TopologyApi.TopologyLeg; orderId?: number; orderNo?: string }[],
  planId?: number
): RouteView | null => {
  const points: { lng: number; lat: number }[] = []
  const orderNos: string[] = []
  let distanceKm = 0
  let realSegments = 0
  rows.forEach((row) => {
    const leg = row.leg
    const stored = (leg.navigationPolyline ?? [])
      .filter((p) => p.longitude != null && p.latitude != null)
      .map((p) => ({ lng: Number(p.longitude), lat: Number(p.latitude) }))
    const key = legKey(leg)
    const cached = legRoadCache.value.get(key)
    const hasCoords =
      leg.fromLongitude != null && leg.fromLatitude != null && leg.toLongitude != null && leg.toLatitude != null
    if (hasCoords) {
      ensureLegRoad(key, leg.fromLongitude!, leg.fromLatitude!, leg.toLongitude!, leg.toLatitude!)
    }
    const fallback = hasCoords
      ? [
          { lng: Number(leg.fromLongitude), lat: Number(leg.fromLatitude) },
          { lng: Number(leg.toLongitude), lat: Number(leg.toLatitude) }
        ]
      : []
    const real = stored.length >= 2 ? stored : (cached && cached.length >= 2 ? cached : null)
    const road = real ?? fallback
    if (real) realSegments++
    road.forEach((p, i) => {
      if (i === 0 && points.length) {
        const prev = points[points.length - 1]
        if (Math.abs(prev.lng - p.lng) < 1e-6 && Math.abs(prev.lat - p.lat) < 1e-6) return
      }
      points.push(p)
    })
    distanceKm += Number(leg.distanceKm || 0)
    if (row.orderNo && !orderNos.includes(row.orderNo)) orderNos.push(row.orderNo)
  })
  if (points.length < 2) return null
  const driver = rows.map((r) => r.leg).find((l) => l.driverName)?.driverName
  const lastOrderNo = orderNos[orderNos.length - 1]
  return {
    key: `order-seg-${planId ?? 0}-${plate}`,
    planId,
    color: plateColorMap.value.get(plate) || '#909399',
    title: `${plate} 任务段${lastOrderNo ? `（到订单 ${lastOrderNo}）` : ''}`,
    orderNos,
    orderCount: orderNos.length,
    stops: [],
    locatedStops: [],
    driverText: driver || '',
    distanceKm,
    distanceText: distanceKm ? distanceKm.toFixed(1) : '-',
    points,
    realSegments,
    totalSegments: rows.length,
    windowText: windowTextOf(rows.map((r) => r.leg)),
    plateNo: plate
  }
}

/** 车牌 → 车辆线颜色：订单视角与地图保持同一套配色（换车即换色） */
const plateColorMap = computed(() => {
  const map = new Map<string, string>()
  routes.value.forEach((r) => {
    const plate = vehicles.value.find((v) => v.plateNo && r.title.includes(v.plateNo))?.plateNo
    if (plate) map.set(plate, r.color)
  })
  // 多段联运的换乘车辆（算法单车经停明细里不出现）续用调色板里未被占用的颜色，
  // 保证「订单视角 / 车辆视角 / 地图」三处同一台车同一种颜色。
  topologyLegs.value.forEach(({ leg }) => {
    if (leg.plateNo && !map.has(leg.plateNo)) {
      map.set(leg.plateNo, ROUTE_COLORS[map.size % ROUTE_COLORS.length])
    }
  })
  return map
})

/** 可绘制（至少 2 个带坐标的经停点）的线路：为空时页面给出明确提示而不是空白地图 */
const drawableRoutes = computed(() => mapRoutes.value.filter((r) => r.points.length >= 2))

/** 多段联运交接（按订单）：哪个订单在哪一站交给谁（转运站点工作人员 / 其他司机） */
const linkOrders = computed(() => {
  const shown = activePlanId.value
    ? plans.value.filter((p) => p.id === activePlanId.value)
    : plans.value
  const orderIds = new Set<number>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.orderId && orderIds.add(i.orderId)))
  const list = topologies.value
    .filter((t) => t && t.orderId != null && orderIds.has(t.orderId))
    .map((t) => {
      const legs = (t.legs ?? []).map((leg, index) => {
        // 交接对象：本段结束需要交接时，指向下一段的司机（其他司机）或站点工作人员
        const next = (t.legs ?? [])[index + 1]
        const handover = (t.handovers ?? []).find((h) => h.stationName && h.stationName === leg.toStationName)
        let handoverTarget = ''
        if (leg.handoverRequired || handover) {
          const toDriver = handover?.toDriverName || next?.driverName
          const toPlate = handover?.toPlateNo || next?.plateNo
          handoverTarget = toDriver
            ? `${toDriver}${toPlate ? `（${toPlate}）` : ''}`
            : (toPlate ? `车辆 ${toPlate}` : '转运站点工作人员')
        }
        // 用地图上"该车牌对应车辆线"的颜色，保证右栏与地图颜色一致（换车=换色，一眼看出联运）
        return { ...leg, handoverTarget, color: plateColorMap.value.get(leg.plateNo || '') || '#909399' }
      })
      // 司机（车辆）归堆用：一单由多段组成时，按承运该单的第一段车辆归堆
      const legList = t.legs ?? []
      const first = legList[0]
      const last = legList[legList.length - 1]
      return {
        key: `link-${t.orderId}`,
        orderId: t.orderId,
        orderNo: t.orderNo || `#${t.orderId}`,
        totalLegs: t.totalLegs ?? legs.length,
        transferCount: t.transferCount ?? 0,
        durationMinutes: t.totalDurationMinutes,
        legs,
        plateKey: first?.plateNo || first?.driverName || '未知车辆',
        driverName: first?.driverName || '',
        startTime: first?.estimatedDeparture || first?.estimatedArrival || '',
        endTime: last?.estimatedArrival || last?.estimatedDeparture || '',
        /** 该司机任务段内的第几单（1 = 第一单，N = 最后一单） */
        seqInDriver: 0,
        /** 该司机在本方案内一共负责几单 */
        driverOrderCount: 0
      }
    })
  // 订单视角排序：同一司机（车辆）的订单归在一堆，堆内按该司机任务段的执行顺序（第一单 → 最后一单，
  // 取承运段离站时间）从上到下排；依次点击即可把地图上的线一段段接成这名司机的整条任务段路线。
  list.sort((a, b) => {
    const byDriver = a.plateKey.localeCompare(b.plateKey)
    if (byDriver !== 0) return byDriver
    const byTime = (a.startTime || '').localeCompare(b.startTime || '')
    if (byTime !== 0) return byTime
    return (a.orderId ?? 0) - (b.orderId ?? 0)
  })
  const counts = new Map<string, number>()
  list.forEach((o) => counts.set(o.plateKey, (counts.get(o.plateKey) || 0) + 1))
  let lastPlate = ''
  let seq = 0
  list.forEach((o) => {
    if (o.plateKey !== lastPlate) {
      lastPlate = o.plateKey
      seq = 0
    }
    o.seqInDriver = ++seq
    o.driverOrderCount = counts.get(o.plateKey) || 1
  })
  return list
})

/** 订单视角：按司机（车辆）归堆后的分组（堆内已按任务段顺序排好），用于列表里的分组标题 */
const linkOrderGroups = computed(() => {
  type LinkOrder = (typeof linkOrders.value)[number]
  const groups: { key: string; title: string; color: string; orders: LinkOrder[] }[] = []
  linkOrders.value.forEach((o) => {
    let g = groups.find((x) => x.key === o.plateKey)
    if (!g) {
      g = {
        key: o.plateKey,
        title: `${o.plateKey}${o.driverName && o.driverName !== o.plateKey ? ` · ${o.driverName}` : ''}`,
        color: plateColorMap.value.get(o.plateKey) || '#909399',
        orders: []
      }
      groups.push(g)
    }
    g.orders.push(o)
  })
  return groups
})

/** 组装"每车一条线路"：按 visitSequence 排序，累计分段里程 */

/** 转接站信息：站点名称 → 转接目标描述（用于车辆视角地图标注 & route-card 显示） */
const handoverStationMap = computed(() => {
  const map = new Map<string, { toDriverName?: string; toPlateNo?: string; fromDriverName?: string; fromPlateNo?: string }>()
  topologies.value.forEach((t) => {
    (t.handovers ?? []).forEach((h) => {
      if (h.stationName) {
        map.set(h.stationName, {
          toDriverName: h.toDriverName,
          toPlateNo: h.toPlateNo,
          fromDriverName: h.fromDriverName,
          fromPlateNo: h.fromPlateNo
        })
      }
    })
  })
  return map
})

/** 判断站点是否为转接站，并返回转接描述文本 */
const handoverInfoFor = (stationId?: number, stationNameStr?: string): string => {
  const name = stationNameStr || stationName(stationId)
  if (!name) return ''
  const h = handoverStationMap.value.get(name)
  if (!h) return ''
  const target = h.toDriverName
    ? `${h.toDriverName}${h.toPlateNo ? `（${h.toPlateNo}）` : ''}`
    : (h.toPlateNo ? `车辆 ${h.toPlateNo}` : '转运站点工作人员')
  return `交给 ${target}`
}

type LinkLeg = TopologyApi.TopologyLeg & { handoverTarget?: string }

/**
 * 按「真实车辆」组装线路：数据口径是运输段（transport_leg），多段联运时包含换乘车辆，
 * 而不是算法返回的单车经停明细（明细会把跨片区订单也挂在同一台车上，导致车辆视角错位）。
 *
 * 每台车按预计到达时间串联它的各段：有高德轨迹段直接用（后端落库的真实道路），
 * ESTIMATED 段按需补一次路网并缓存（10 分钟），仍拿不到才用两点直线兜底（虚线并标注）。
 */
const buildLegRoutes = (orderIdFilter?: Set<number>): RouteView[] => {
  const orders = linkOrders.value.filter(
    (o) => !orderIdFilter || (o.orderId != null && orderIdFilter.has(o.orderId))
  )
  if (!orders.length) return []
  // 分组口径：同一套方案（= 一个任务时间窗）+ 同一台车。
  // 绝不把不同方案/不同时间窗的运输段并成一条线：否则同一天多套方案会被画成
  // "一台车跑了一整天、做完一单瞬移到别处接单"，与"一车一时窗任务段"的真实口径不符。
  const planIdOf = (orderId?: number) =>
    plans.value.find((p) => (p.items ?? []).some((i) => i.orderId === orderId))?.id
  const groups = new Map<string, { leg: LinkLeg; orderId?: number; orderNo?: string; planId?: number }[]>()
  orders.forEach((o) => {
    const planId = planIdOf(o.orderId)
    o.legs.forEach((leg) => {
      const plate = leg.plateNo || leg.driverName || '未知车辆'
      const groupKey = `${planId ?? 0}-${plate}`
      if (!groups.has(groupKey)) groups.set(groupKey, [])
      groups.get(groupKey)!.push({ leg: leg as LinkLeg, orderId: o.orderId, orderNo: o.orderNo, planId })
    })
  })
  const list: RouteView[] = []
  groups.forEach((rows, groupKey) => {
    const plate = rows[0]?.leg.plateNo || rows[0]?.leg.driverName || '未知车辆'
    const planId = rows[0]?.planId
    const sorted = [...rows].sort((a, b) => {
      const ta = a.leg.estimatedArrival || ''
      const tb = b.leg.estimatedArrival || ''
      if (ta !== tb) return ta.localeCompare(tb)
      return (a.leg.legSequence ?? 0) - (b.leg.legSequence ?? 0)
    })
    const points: { lng: number; lat: number }[] = []
    const legRows: VehicleLegRow[] = []
    const locatedStops: { stop: RouteStop; lng: number; lat: number }[] = []
    let realSegments = 0
    sorted.forEach((row, index) => {
      const leg = row.leg
      const key = legKey(leg)
      const stored = (leg.navigationPolyline ?? [])
        .filter((p) => p.longitude != null && p.latitude != null)
        .map((p) => ({ lng: Number(p.longitude), lat: Number(p.latitude) }))
      const cached = legRoadCache.value.get(key)
      const hasCoords =
        leg.fromLongitude != null && leg.fromLatitude != null && leg.toLongitude != null && leg.toLatitude != null
      if (hasCoords) {
        ensureLegRoad(key, leg.fromLongitude!, leg.fromLatitude!, leg.toLongitude!, leg.toLatitude!)
      }
      const fallback = hasCoords
        ? [
            { lng: Number(leg.fromLongitude), lat: Number(leg.fromLatitude) },
            { lng: Number(leg.toLongitude), lat: Number(leg.toLatitude) }
          ]
        : []
      const real = stored.length >= 2 ? stored : (cached && cached.length >= 2 ? cached : null)
      const road = real ?? fallback
      const estimated = !real
      if (!estimated) realSegments++
      road.forEach((p, i) => {
        // 段与段的衔接点重合：跳过下一段的首点，避免重复点造成线头/播放抖动
        if (i === 0 && index > 0) return
        points.push(p)
      })
      legRows.push({
        seq: index + 1,
        orderId: row.orderId,
        orderNo: row.orderNo,
        fromName: leg.fromStationName || '—',
        toName: leg.toStationName || '—',
        plateNo: leg.plateNo,
        driverName: leg.driverName,
        estimated,
        handoverText: leg.handoverTarget ? `在 ${leg.toStationName || '本站'} 交给 ${leg.handoverTarget}` : '',
        timeText: timeText(leg.estimatedArrival),
        timeRangeText: timeRangeText(leg.estimatedDeparture, leg.estimatedArrival),
        distanceText: leg.distanceKm != null ? Number(leg.distanceKm).toFixed(1) : '-'
      })
      // 地图经停点标记：每段的到达站（换乘段标「途经」，最后一段标「派送」）
      if (hasCoords) {
        locatedStops.push({
          stop: {
            stationId: undefined,
            stationName: leg.toStationName,
            actionType: leg.handoverTarget ? 6 : 3,
            visitSequence: index + 1,
            orderNo: row.orderNo
          } as unknown as RouteStop,
          lng: Number(leg.toLongitude),
          lat: Number(leg.toLatitude)
        })
      }
    })
    const orderNos = [...new Set(rows.map((r) => r.orderNo).filter(Boolean) as string[])]
    const distanceKm = sorted.reduce((sum, r) => sum + (Number(r.leg.distanceKm) || 0), 0)
    const driver = sorted.map((r) => r.leg).find((l) => l.driverName)?.driverName
    list.push({
      key: `leg-${groupKey}`,
      color: plateColorMap.value.get(plate) || '#909399',
      title: `${planId ? `方案 #${planId} · ` : ''}${plate}${driver ? ` · ${driver}` : ''}`,
      orderNos,
      orderCount: orderNos.length,
      stops: [],
      locatedStops,
      driverText: driver || '',
      distanceKm,
      distanceText: distanceKm ? distanceKm.toFixed(1) : '-',
      points,
      realSegments,
      totalSegments: legRows.length,
      legs: legRows,
      windowText: windowTextOf(sorted.map((r) => r.leg)),
      planId,
      plateNo: plate
    })
  })
  return list
}

/** 按车辆视角的线路（运输段口径）；没有运输段时 visibleRoutes 会退回算法经停明细 */
const vehicleLegRoutes = computed<RouteView[]>(() => buildLegRoutes())

/** 组装"每车一条线路"：按 visitSequence 排序，累计分段里程 */
const buildRoutes = () => {
  const list: RouteView[] = []
  // 颜色在所有方案间全局递增：保证同屏每台车颜色都不同（原来按方案重置，多车会撞色）
  let colorIndex = 0
  plans.value.forEach((plan) => {
    const byVehicle = new Map<number, RouteStop[]>()
    ;(plan.items ?? []).forEach((item) => {
      const key = item.vehicleId ?? 0
      if (!byVehicle.has(key)) byVehicle.set(key, [])
      byVehicle.get(key)!.push(item)
    })
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
/** 手动切换的"坐标示意图"模式：地图加载不出来时也能完整演示（播放同样可用） */
const showSchematic = ref(false)
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
    // 弹窗容器可能是懒渲染出来的，首帧尺寸为 0 会让地图看起来"空白"：
    // 延迟触发一次 resize + 重画，避免必须手动缩放才能看到底图与线路
    window.setTimeout(() => {
      try {
        if (typeof map.resize === 'function') map.resize()
        if (mapRef.value && mapRef.value.clientWidth > 0 && typeof map.setViewport === 'function') {
          redraw()
        }
      } catch (e) {
        console.warn('地图 resize 失败', e)
      }
    }, 260)
  } catch (e) {
    console.error('调度可视化地图初始化失败', e)
    mapError.value = '地图初始化失败'
  }
}

const toggleSchematic = () => {
  showSchematic.value = !showSchematic.value
  if (!showSchematic.value && mapReady.value) redraw()
}

const clearOverlays = () => {
  overlays.value.forEach((o) => map?.removeOverlay(o))
  overlays.value = []
  moverMarkers.value = {}
}

/**
 * 彻底释放地图实例。
 * Dialog 关闭后容器 DOM 会被销毁重建，而 BMapGL 实例仍指向旧节点 → 再次打开就是空白地图
 * （"越优化越看不见"就是这个原因）。所以每次关闭都要销毁实例，下次打开重新初始化。
 */
const resetMap = () => {
  try {
    if (map && typeof map.clearOverlays === 'function') map.clearOverlays()
  } catch (e) { /* ignore */ }
  try {
    if (map && typeof map.destroy === 'function') map.destroy()
  } catch (e) { /* ignore */ }
  map = null
  mapReady.value = false
  mapError.value = ''
  overlays.value = []
  moverMarkers.value = {}
}

/** 地图上画线路 + 站点标记 + 播放用车辆 marker */
const drawMap = () => {
  if (!mapReady.value || !map) return
  const BMapGL = window.BMapGL
  clearOverlays()
  const allPoints: any[] = []
  mapRoutes.value.forEach((route) => {
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
    // 点线路 = 选中这台车（只显示它的线路与作业点，其余车辆自动隐藏）
    try {
      polyline.addEventListener('click', () => selectVehicleByKey(route.key))
    } catch (e) { /* 老版本 SDK 不支持事件绑定：不影响绘图 */ }
    map.addOverlay(polyline)
    overlays.value.push(polyline)
    allPoints.push(...path)
    route.locatedStops.forEach((located, index) => {
      const stop = located.stop
      const bd = gcj02ToBd09(located.lng, located.lat)
      const point = new BMapGL.Point(bd.lng, bd.lat)
      const marker = new BMapGL.Circle(point, 8, { strokeColor: '#fff', strokeWeight: 2, fillColor: route.color, fillOpacity: 1 })
      try {
        marker.addEventListener('click', () => selectVehicleByKey(route.key))
      } catch (e) { /* ignore */ }
      map.addOverlay(marker)
      overlays.value.push(marker)
      const label = new BMapGL.Label(
        // 地图上把"在哪儿做什么"标清楚：序号 · 操作（货运标件数，客运不标人数）· 站名
        `${index + 1}. ${actionLabel(stop.actionType)}${quantityText(stop)} · ${stop.stationName || stationName(stop.stationId)}`,
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

      // 转接站标注：如果该站点是转接点，额外绘制醒目菱形标记
      const stopName = stop.stationName || stationName(stop.stationId)
      const _handover = stopName ? handoverStationMap.value.get(stopName) : undefined
      if (_handover) {
        const hoTarget = _handover.toDriverName
          ? `交给 ${_handover.toDriverName}${_handover.toPlateNo ? `(${_handover.toPlateNo})` : ''}`
          : (_handover.toPlateNo ? `交给 ${_handover.toPlateNo}` : '交给转运站点')
        // 橙色菱形标记
        const hoIcon = new BMapGL.Icon(handoverIconUrl(), new BMapGL.Size(28, 28), {
          anchor: new BMapGL.Size(14, 14)
        })
        const hoMarker = new BMapGL.Marker(new BMapGL.Point(bd.lng, bd.lat), { icon: hoIcon, offset: new BMapGL.Size(0, -18) })
        map.addOverlay(hoMarker)
        overlays.value.push(hoMarker)
        // 转接信息标签
        const hoLabel = new BMapGL.Label(
          `🔄 ${hoTarget}`,
          { position: point, offset: new BMapGL.Size(12, -44) }
        )
        hoLabel.setStyle({
          color: '#e6a23c',
          fontSize: '12px',
          fontWeight: 'bold',
          border: '1px solid #e6a23c',
          padding: '2px 8px',
          background: '#fff8e1',
          borderRadius: '4px'
        })
        map.addOverlay(hoLabel)
        overlays.value.push(hoLabel)
      }

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
    // 车头 marker（播放时沿线路移动）：用线路同色圆点，播放时也能分清是哪台车
    const moverIcon = new BMapGL.Icon(moverIconUrl(route.color), new BMapGL.Size(22, 22), {
      anchor: new BMapGL.Size(11, 11)
    })
    const mover = new BMapGL.Marker(path[0], { icon: moverIcon })
    map.addOverlay(mover)
    overlays.value.push(mover)
    moverMarkers.value[route.key] = { marker: mover, path }
  })
  if (allPoints.length) {
    map.setViewport(allPoints)
  }

}
/** 转接站菱形图标（橙色，带"转"字） */
const handoverIconUrl = () => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">
    <polygon points="16,2 30,16 16,30 2,16" fill="#e6a23c" stroke="#ffffff" stroke-width="2"/>
    <text x="16" y="20" text-anchor="middle" fill="#ffffff" font-size="13" font-weight="bold">转</text>
  </svg>`
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

/** 箭头图标（SVG data URI，颜色随线路） */
const arrowIcon = (color: string) => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">
    <path d="M16 3 L27 28 L16 22 L5 28 Z" fill="${color}" stroke="#ffffff" stroke-width="2" stroke-linejoin="round"/>
  </svg>`
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg)
}

/** 播放中的车头圆点图标（颜色随线路） */
const moverIconUrl = (color: string) => {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32">
    <circle cx="16" cy="16" r="11" fill="${color}" stroke="#ffffff" stroke-width="4"/>
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
  return mapRoutes.value.map((route) => {
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
  const pts = mapRoutes.value.flatMap((r) => r.points)
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
  return mapRoutes.value.map((route) => {
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
      const route = mapRoutes.value.find((r) => r.key === p.key)
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
  // 每次打开都重建地图实例（上一个实例绑定的 DOM 已被 Dialog 销毁）
  resetMap()
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
    // 方案内订单的运输链（多段联运交接：哪个订单在哪交给谁）
    const orderIds = [...new Set(plans.value.flatMap((p) => (p.items ?? [])
      .map((i) => i.orderId).filter((id): id is number => id != null)))].slice(0, 10)
    topologies.value = orderIds.length
      ? (await Promise.all(orderIds.map((id) => TopologyApi.getTopologyByOrder(id).catch(() => null))))
          .filter((t): t is TopologyApi.OrderTopologyVO => !!t)
      : []
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
    // 默认只看一套方案（= 一个任务时间窗）。多套方案一起展示时，同一台车不同窗口的运输段
    // 会被误读成"这台车跑了一整天、做完一单瞬移到别处接单"；需要全局视角时手动点「全部方案」。
    activePlanId.value = plans.value[0]?.id ?? 0
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
    else {
      stopPlay()
      resetMap()
    }
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
.viz-map-empty {
  position: absolute;
  left: 50%;
  top: 46%;
  transform: translate(-50%, -50%);
  z-index: 5;
  max-width: 76%;
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-color-warning);
  background: rgba(255, 255, 255, 0.95);
  border: 1px dashed var(--el-color-warning);
  border-radius: 8px;
  padding: 10px 14px;
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
.viz-vehicle-legend {
  position: absolute;
  left: 10px;
  bottom: 56px;
  z-index: 3;
  max-width: 56%;
  max-height: 40%;
  overflow-y: auto;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid #c7d8ea;
  border-radius: 6px;
  padding: 6px 8px;
}
.legend-vehicle {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  line-height: 1.7;
  white-space: nowrap;
}
.legend-vehicle-color {
  width: 11px;
  height: 11px;
  border-radius: 3px;
  display: inline-block;
  flex-shrink: 0;
}
.legend-vehicle-name {
  color: #123f6e;
  font-weight: 600;
}
.legend-vehicle-meta {
  color: var(--el-text-color-secondary);
}
/* 点图例/地图线路即选中该车：只显示这一台车的线路与作业点（换车自动隐藏上一条） */
.legend-vehicle-clickable {
  cursor: pointer;
}
.legend-vehicle-clickable.picked {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
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
.panel-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.panel-tab {
  padding: 4px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  cursor: pointer;
  user-select: none;
}
.panel-tab.active {
  background: #1f5e9e;
  border-color: #1f5e9e;
  color: #fff;
}
.panel-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.route-card.selectable,
.order-card.selectable {
  cursor: pointer;
}
.route-card.picked,
.order-card.picked {
  border-color: #1f5e9e;
  box-shadow: 0 0 0 1px #1f5e9e inset;
}
.route-card.dimmed,
.order-card.dimmed {
  opacity: 0.45;
}
.route-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.order-card {
  border: 1px solid #c7d8ea;
  background: #f5f9ff;
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
}
.order-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
}
.order-group {
  margin-bottom: 12px;
}
.order-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0 6px;
  border-bottom: 1px dashed #c7d8ea;
  margin-bottom: 8px;
}
.order-group-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.order-group-title {
  font-size: 13px;
  font-weight: 600;
  color: #123f6e;
}
.order-group-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.order-seq {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: #fff;
  background: #123f6e;
  border-radius: 999px;
  padding: 1px 7px;
}
.order-no {
  font-size: 13px;
  font-weight: 600;
  color: #123f6e;
}
.order-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.order-leg {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 0;
}
.leg-badge {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  line-height: 1.5;
  font-weight: 500;
  white-space: nowrap;
}
.est-badge {
  background: #fdf6ec;
  color: #e6a23c;
  border: 1px solid #faecd8;
}
.real-badge {
  background: #ecf5ff;
  color: #409eff;
  border: 1px solid #d9ecff;
}
.order-est-hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
  padding: 4px 8px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  font-size: 11px;
  color: #e6a23c;
  line-height: 1.5;
  .el-icon {
    flex-shrink: 0;
  }
}
.leg-color {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex-shrink: 0;
}
.leg-seq {
  color: var(--el-text-color-placeholder);
  width: 12px;
  text-align: right;
}
.leg-text {
  color: var(--el-text-color-primary);
}
.order-transfer {
  margin-left: 28px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.6;
}
.link-card {
  border: 1px solid #c7d8ea;
  background: #f5f9ff;
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 10px;
}
.link-title {
  font-size: 13px;
  font-weight: 600;
  color: #123f6e;
  margin-bottom: 6px;
}
.link-order {
  margin-bottom: 8px;
}
.link-order-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: #1f5e9e;
}
.link-order-meta {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.link-leg {
  display: grid;
  grid-template-columns: 18px 1fr;
  gap: 6px;
  font-size: 12px;
  padding: 2px 0;
  color: var(--el-text-color-primary);
}
.link-seq {
  color: #1f5e9e;
  text-align: right;
}
.link-leg em {
  font-style: normal;
  color: var(--el-text-color-secondary);
}
.link-transfer {
  color: #e6a23c;
  font-weight: 600;
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

.stop-handover {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 4px 0 4px 26px;
  color: #e6a23c;
  background: #fff8e1;
  margin: 2px 0;
  border-radius: 4px;
  border-left: 3px solid #e6a23c;
}
.stop-handover-icon {
  font-size: 14px;
}
.stop-handover-text {
  font-weight: 600;
}
</style>
