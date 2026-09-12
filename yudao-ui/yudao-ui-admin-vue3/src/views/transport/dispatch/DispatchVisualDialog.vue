<template>
  <Dialog v-model="visible" title="调度结果可视化" width="1180px">
    <div v-loading="loading" class="viz">
      <!-- 方案切换 + 汇总 -->
      <div class="viz-head">
        <el-radio-group v-if="plans.length > 1" v-model="activePlanId" size="small" @change="redraw">
          <el-radio-button v-for="p in plans" :key="'r-' + p.id" :value="p.id!">
            方案 #{{ p.id }}
          </el-radio-button>
          <el-radio-button :value="0">全部方案（对比用）</el-radio-button>
          <!-- 说明：默认只选中一套方案 = 一个任务段；把多套方案叠在一张图上会把路线画乱 -->
        </el-radio-group>
        <div v-if="plans.length > 1" class="viz-scope-tip">
          默认只显示 <b>一个任务段</b>（方案 #{{ activePlanId || plans[0].id }}）；跨区订单是另一段，切方案分屏看，不叠加
        </div>
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
              >{{ pt.label }}</text>
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
            {{ showSchematic ? '坐标示意图（仅按真实经纬度连真实道路轨迹）' : (mapError ? '地图不可用，已切换为坐标示意图' : '地图加载中…') }}
          </div>
          <div v-if="!drawableRoutes.length" class="viz-map-empty">
            该方案暂无可用真实道路轨迹（缺站点经纬度，或高德路网不可用）——已按"不画直线"处理。
            请在「站点管理」补齐站点经纬度，并确认后端已配置高德 key 后重新生成方案。
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
            <span class="legend-item"><span class="legend-arrow">➤</span>行驶方向</span>
            <span class="legend-item"><span class="legend-dot">n</span>经停顺序</span>
            <span class="legend-item legend-note">只画真实道路轨迹（缺路网数据的段不连线）</span>
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
              : '按先后列出每台车的行程链；点行程链看该车整条连续线路，点订单看这单的分段路线' }}
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
                </template>
                <template v-else>
                  {{ route.stops.length }} 站 · {{ route.distanceText }} km · 订单 {{ route.orderCount }} 单
                </template>
                <template v-if="route.driverText"> · {{ route.driverText }}</template>
              </div>
              <!-- 运输段口径（真实车辆）：一段一行，写清从哪到哪、哪单、是真实道路还是缺路网轨迹 -->
              <template v-if="route.legs">
                <div v-for="lg in route.legs" :key="'leg' + lg.seq" class="stop-row">
                  <span class="stop-seq">{{ lg.seq }}</span>
                  <span class="stop-dot" :class="lg.estimated ? 'act-seat' : 'act-deliver'"></span>
                  <span class="stop-act" :class="lg.estimated ? 'act-seat' : 'act-deliver'">
                    {{ lg.estimated ? '缺路网轨迹' : '真实道路' }}
                  </span>
                  <span class="stop-station">{{ lg.fromName }} → {{ lg.toName }}</span>
                  <span v-if="lg.orderNo" class="stop-order">{{ lg.orderNo }}</span>
                  <span class="stop-time">{{ lg.timeText }}</span>
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

          <!-- 视角三：按订单看"订单一段接一段"的先后关系；同一台车串成一条行程链 -->
          <template v-else>
            <div v-if="!linkOrders.length" class="text-gray-400 text-sm">
              本方案暂无订单运输链（或该订单还未生成运输段）
            </div>

            <!-- 行程链：同一台车的订单按时间先后串成一条连续线路（演示主线：346 司机本职跑线 + 途中取派货） -->
            <div v-if="journeyChains.length" class="journey-wrap">
              <div class="journey-title">各车行程链（订单一段接一段，按先后）</div>
              <div
                v-for="c in journeyChains"
                :key="c.key"
                class="journey-card"
                :class="{ picked: selectedJourneyKey === c.key, dimmed: selectedJourneyKey && selectedJourneyKey !== c.key }"
                @click="selectJourney(c.key)"
              >
                <div class="journey-head">
                  <span class="route-color" :style="{ background: c.color }"></span>
                  <span class="journey-vehicle">{{ c.plateNo }}</span>
                  <span v-if="c.driverName" class="journey-driver">· {{ c.driverName }}</span>
                  <span class="journey-meta">
                    {{ c.legCount }} 段 · 订单 {{ c.orders.length }} 单 · {{ c.distanceText }} km
                  </span>
                </div>
                <div class="journey-line">
                  <span class="journey-end">{{ c.fromName }}</span>
                  <span class="journey-arrow">→</span>
                  <span class="journey-end">{{ c.toName }}</span>
                </div>
                <div v-for="(o, oi) in c.orders" :key="o.orderNo" class="journey-order">
                  <span class="journey-seq">{{ oi + 1 }}</span>
                  <span class="journey-order-no">订单 {{ o.orderNo }}</span>
                  <span class="journey-order-text">{{ o.fromName }} → {{ o.toName }}</span>
                  <span v-if="o.timeText" class="journey-order-time">{{ o.timeText }}</span>
                  <span v-if="o.handoverTarget" class="journey-handover">⇄ 交 {{ o.handoverTarget }}</span>
                </div>
              </div>
            </div>

            <div
              v-for="o in linkOrders"
              :key="o.key"
              class="order-card selectable"
              :class="{ dimmed: selectedOrderKey && selectedOrderKey !== o.key, picked: selectedOrderKey === o.key }"
              @click="selectOrder(o.key)"
            >
              <div class="order-head">
                <span class="order-no">订单 {{ o.orderNo }}</span>
                <span class="order-meta">
                  {{ o.totalLegs }} 段 · 换乘 {{ o.transferCount }} 次
                  <template v-if="o.durationMinutes"> · 约 {{ o.durationMinutes }} 分钟</template>
                </span>
              </div>
              <div v-if="selectedOrderKey === o.key && o.noRoadLegs" class="order-est-hint">
                <el-icon><WarningFilled /></el-icon>
                <span>该订单有 {{ o.noRoadLegs }} 段暂无真实道路轨迹：地图不画直线，只显示已有真实道路的分段。</span>
              </div>
              <div v-for="(leg, i) in o.legs" :key="i" class="order-leg">
                <span class="leg-color" :style="{ background: leg.color }"></span>
                <span class="leg-seq">{{ i + 1 }}</span>
                <span class="leg-text">
                  <b>{{ leg.plateNo || '车辆' }}</b>{{ leg.driverName ? ` · ${leg.driverName}` : '' }}
                  ：{{ leg.fromStationName || '—' }} → {{ leg.toStationName || '—' }}
                </span>
                <el-tooltip v-if="leg.noRoad" content="该路段暂无真实道路数据，地图不画直线（避免伪造轨迹）" placement="top">
                  <span class="leg-badge est-badge">缺路网轨迹</span>
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
  /** true = 该段暂无真实道路轨迹（地图上不连线，列表标注"缺路网轨迹"） */
  estimated: boolean
  handoverText: string
  timeText: string
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
  /** 全部真实道路点（按顺序拼接，供播放车头沿线路移动；缺口段直接跳过，不产生直线） */
  points: { lng: number; lat: number }[]
  /**
   * 连续的真实道路折线：每一条都是一段不中断的真实轨迹。
   * 缺路网数据的运输段在这里**断开**（不画直线），所以不会出现"两点直连"的假轨迹。
   */
  polylines?: { lng: number; lat: number }[][]
  /** 该车轨迹中有多少段来自真实道路（AMAP）；其余为缺路网数据（不绘制） */
  realSegments: number
  totalSegments: number
  /** 按车辆视角：该车真实执行的运输段（存在时优先按它展示，而不是算法单车经停明细） */
  legs?: VehicleLegRow[]
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
/** 按订单视角下选中的行程链（journeyChains.key）；为空=显示全部车辆的连续线路 */
const selectedJourneyKey = ref('')

const switchTab = (tab: 'all' | 'vehicle' | 'order') => {
  panelTab.value = tab
  selectedVehicleKey.value = ''
  selectedOrderKey.value = ''
  selectedJourneyKey.value = ''
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
  selectedOrderKey.value = selectedOrderKey.value === key ? '' : key
  selectedJourneyKey.value = ''
  redraw()
}

/** 点选某台车的行程链：地图画这台车"一段接一段"的整条连续线路 */
const selectJourney = (key: string) => {
  selectedJourneyKey.value = selectedJourneyKey.value === key ? '' : key
  selectedOrderKey.value = ''
  redraw()
}

/**
 * 取某段运输段的真实道路折线。
 *
 * 库里有 AMAP 轨迹就用库里的；没有就按需向后端补一次路网（10 分钟缓存）。
 * **补不到时返回空数组——绝不回退成"两点直连"**：演示里那条斜穿城市的直线就是这么来的，
 * 需求明确要求"把直线去掉"，所以缺路网数据的段宁可不画（列表里标注"缺路网轨迹"）。
 */
const resolveLegRoad = (leg: TopologyApi.TopologyLeg): { lng: number; lat: number }[] => {
  // 该段路网来源不是高德真实道路（EUCLIDEAN 直线兜底）→ 不画，避免斜穿城市的假轨迹。
  // 只在高德可用（navigationSource=AMAP）且轨迹点足够时才绘制真实道路。
  if (leg.navigationSource && leg.navigationSource !== 'AMAP') return []
  const stored = (leg.navigationPolyline ?? [])
    .filter((p) => p.longitude != null && p.latitude != null)
    .map((p) => ({ lng: Number(p.longitude), lat: Number(p.latitude) }))
  if (stored.length >= 2) return stored
  const cached = legRoadCache.value.get(legKey(leg))
  if (cached && cached.length >= 2) return cached
  if (leg.fromLongitude != null && leg.fromLatitude != null
    && leg.toLongitude != null && leg.toLatitude != null) {
    ensureLegRoad(legKey(leg), leg.fromLongitude, leg.fromLatitude, leg.toLongitude, leg.toLatitude)
  }
  return []
}

/** 该段是否有真实道路轨迹（无 = 列表标注"缺路网轨迹"，地图不连线） */
const legHasRoad = (leg: TopologyApi.TopologyLeg) => resolveLegRoad(leg).length >= 2

/** 订单视角：把一张订单拆成多段真实道路折线（不同车辆不同颜色；缺路网的段不画直线） */
const buildOrderLegRoutes = (order: OrderChain): RouteView[] => {
  const plan = plans.value.find((p) => (p.items ?? []).some((i) => i.orderId === order.orderId))
  const list: RouteView[] = []
  order.legs.forEach((leg, index) => {
    const road = resolveLegRoad(leg)
    if (road.length < 2) return
    list.push({
      key: `order-${order.orderId}-leg-${index}`,
      planId: plan?.id,
      color: leg.color || '#909399',
      title: `${leg.plateNo || '车辆'} 第 ${index + 1} 段`,
      orderNos: [order.orderNo],
      orderCount: 1,
      stops: [],
      locatedStops: [],
      driverText: leg.driverName || '',
      distanceKm: Number(leg.distanceKm || 0),
      distanceText: leg.distanceKm != null ? Number(leg.distanceKm).toFixed(1) : '-',
      points: road,
      polylines: [road],
      realSegments: 1,
      totalSegments: 1
    } as RouteView)
  })
  return list
}

/** 当前选中订单的分段路线（未选中时不画，默认画全部车辆的连续线路） */
const orderLegRoutes = computed<RouteView[]>(() => {
  const order = linkOrders.value.find((o) => o.key === selectedOrderKey.value)
  return order ? buildOrderLegRoutes(order) : []
})

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
  if (panelTab.value === 'order') {
    // 选了行程链 → 画这台车"订单一段接一段"的整条连续线路；
    // 选了单张订单 → 画该单分段；都没选 → 画全部车辆的连续线路（演示主线视图）
    if (selectedJourneyKey.value) {
      const plate = selectedJourneyKey.value.slice('journey-'.length)
      return visibleRoutes.value.filter((r) => r.plateNo === plate)
    }
    if (selectedOrderKey.value) return orderLegRoutes.value
    return visibleRoutes.value
  }
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

/**
 * 多段联运交接（按订单）：哪个订单在哪一站交给谁（转运站点工作人员 / 其他司机）。
 * 结果按"该订单第一段的时间"升序排列 —— 演示时订单要按先后读，不能乱序。
 */
const linkOrders = computed<OrderChain[]>(() => {
  const shown = activePlanId.value
    ? plans.value.filter((p) => p.id === activePlanId.value)
    : plans.value
  const orderIds = new Set<number>()
  shown.forEach((p) => (p.items ?? []).forEach((i) => i.orderId && orderIds.add(i.orderId)))
  return topologies.value
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
        return {
          ...leg,
          handoverTarget,
          color: plateColorMap.value.get(leg.plateNo || '') || '#909399',
          // 缺真实道路轨迹的段：地图不画直线，列表里明确标注（不让演示出现"斜穿城市的直线"）
          noRoad: !legHasRoad(leg)
        }
      })
      const firstTime = legs.map((l) => l.estimatedArrival || '').filter(Boolean).sort()[0] || ''
      return {
        key: `link-${t.orderId}`,
        orderId: t.orderId,
        orderNo: t.orderNo || `#${t.orderId}`,
        totalLegs: t.totalLegs ?? legs.length,
        transferCount: t.transferCount ?? 0,
        durationMinutes: t.totalDurationMinutes,
        noRoadLegs: legs.filter((l) => l.noRoad).length,
        firstTime,
        legs
      }
    })
    .sort((a, b) => {
      if (a.firstTime !== b.firstTime) return (a.firstTime || '9').localeCompare(b.firstTime || '9')
      return (a.orderId ?? 0) - (b.orderId ?? 0)
    })
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

type LinkLeg = TopologyApi.TopologyLeg & {
  handoverTarget?: string
  /** 该段承运车辆的线条颜色（与地图、图例同一套配色） */
  color?: string
  /** 该段没有真实道路轨迹（地图不画直线，列表标注"缺路网轨迹"） */
  noRoad?: boolean
}

/** 一张订单的运输链（按先后排好的分段） */
type OrderChain = {
  key: string
  orderId?: number
  orderNo: string
  totalLegs: number
  transferCount: number
  durationMinutes?: number
  /** 缺真实道路轨迹的段数 */
  noRoadLegs: number
  /** 首段预计到达时间，用于把订单按先后排序 */
  firstTime: string
  legs: LinkLeg[]
}

/** 同一台车的运输段，按"预计到达时间 → 段序号"排序（时间相同才看段号，保证先后稳定） */
const compareLegOrder = (
  a: { leg: TopologyApi.TopologyLeg },
  b: { leg: TopologyApi.TopologyLeg }
) => {
  const ta = a.leg.estimatedArrival || ''
  const tb = b.leg.estimatedArrival || ''
  if (ta !== tb) return ta.localeCompare(tb)
  return (a.leg.legSequence ?? 0) - (b.leg.legSequence ?? 0)
}

/** 行程链里的一张订单（同一台车承运，按先后排列） */
type JourneyOrderRow = {
  orderId?: number
  orderNo: string
  fromName: string
  toName: string
  timeText: string
  handoverTarget?: string
  legCount: number
}

/** 行程链（一台车一条）：订单一段接一段，按先后串起来 */
type JourneyChain = {
  key: string
  plateNo: string
  driverName?: string
  color: string
  legCount: number
  distanceText: string
  orders: JourneyOrderRow[]
  fromName: string
  toName: string
}

/**
 * 各车行程链：把运输段按车辆分组，段内按先后排序，再把同一张订单的段合并成一行。
 *
 * 这就是演示要讲的"司机本职按线路跑，途中一段接一段地取派货"：
 * 346 的司机从始发站出发 → 订单A（中研所→上新街）→ 订单B（黄桷垭→小什字）→ …
 * → 跨片区的那单在龙门浩交给 320 的司机 → 一路到终点站较场口；返程再走另一套调度。
 */
const journeyChains = computed<JourneyChain[]>(() => {
  const chains: JourneyChain[] = []
  const byPlate = new Map<string, { leg: LinkLeg; orderNo: string; orderId?: number }[]>()
  linkOrders.value.forEach((o) => {
    o.legs.forEach((leg) => {
      const plate = leg.plateNo || '未分配车辆'
      if (!byPlate.has(plate)) byPlate.set(plate, [])
      byPlate.get(plate)!.push({ leg, orderNo: o.orderNo, orderId: o.orderId })
    })
  })
  byPlate.forEach((rows, plate) => {
    const sorted = [...rows].sort(compareLegOrder)
    const orders: JourneyOrderRow[] = []
    sorted.forEach(({ leg, orderNo, orderId }) => {
      let row = orders.find((r) => r.orderNo === orderNo)
      if (!row) {
        row = {
          orderId,
          orderNo,
          fromName: leg.fromStationName || '—',
          toName: leg.toStationName || '—',
          timeText: '',
          handoverTarget: undefined,
          legCount: 0
        }
        orders.push(row)
      }
      // 同一张订单多段时，链上写"从首段起点到末段终点"，时间取该单最后一段
      row.toName = leg.toStationName || row.toName
      row.legCount += 1
      if (leg.estimatedArrival) row.timeText = timeText(leg.estimatedArrival)
      if (leg.handoverTarget) row.handoverTarget = leg.handoverTarget
    })
    const first = sorted[0]?.leg
    const last = sorted[sorted.length - 1]?.leg
    const distanceKm = sorted.reduce((sum, r) => sum + (Number(r.leg.distanceKm) || 0), 0)
    chains.push({
      key: `journey-${plate}`,
      plateNo: plate,
      driverName: sorted.map((r) => r.leg.driverName).find(Boolean),
      color: plateColorMap.value.get(plate) || '#909399',
      legCount: sorted.length,
      distanceText: distanceKm.toFixed(1),
      orders,
      fromName: first?.fromStationName || '—',
      toName: last?.toStationName || '—'
    })
  })
  // 段多的车排前面（主线车辆一眼可见）
  return chains.sort((a, b) => b.legCount - a.legCount || a.plateNo.localeCompare(b.plateNo))
})

/**
 * 按「真实车辆」组装线路：数据口径是运输段（transport_leg），多段联运时包含换乘车辆，
 * 而不是算法返回的单车经停明细（明细会把跨片区订单也挂在同一台车上，导致车辆视角错位）。
 *
 * 每台车按预计到达时间串联它的各段：有高德轨迹段直接用（后端落库的真实道路），
 * 缺轨迹的段按需补一次路网并缓存（10 分钟）；**仍拿不到就不画这一段**（宁可断开也不画直线）。
 */
const buildLegRoutes = (orderIdFilter?: Set<number>): RouteView[] => {
  const orders = linkOrders.value.filter(
    (o) => !orderIdFilter || (o.orderId != null && orderIdFilter.has(o.orderId))
  )
  if (!orders.length) return []
  const groups = new Map<string, { leg: LinkLeg; orderId?: number; orderNo?: string }[]>()
  orders.forEach((o) => {
    o.legs.forEach((leg) => {
      const plate = leg.plateNo || leg.driverName || '未知车辆'
      if (!groups.has(plate)) groups.set(plate, [])
      groups.get(plate)!.push({ leg: leg as LinkLeg, orderId: o.orderId, orderNo: o.orderNo })
    })
  })
  const list: RouteView[] = []
  groups.forEach((rows, plate) => {
    const sorted = [...rows].sort(compareLegOrder)
    // 连续真实道路折线：缺路网的段把线断开（runs 里存"一段不间断的真实轨迹"）
    const runs: { lng: number; lat: number }[][] = []
    let current: { lng: number; lat: number }[] = []
    const legRows: VehicleLegRow[] = []
    const locatedStops: { stop: RouteStop; lng: number; lat: number }[] = []
    let realSegments = 0
    sorted.forEach((row, index) => {
      const leg = row.leg
      const hasCoords =
        leg.fromLongitude != null && leg.fromLatitude != null && leg.toLongitude != null && leg.toLatitude != null
      const road = resolveLegRoad(leg)
      const estimated = road.length < 2
      if (!estimated) {
        realSegments++
        road.forEach((p, i) => {
          if (i === 0) {
            // 段与段的衔接点重合：连续时跳过重复首点，避免线头/播放抖动
            const last = current[current.length - 1]
            if (!last || Math.abs(last.lng - p.lng) > 1e-9 || Math.abs(last.lat - p.lat) > 1e-9) {
              current.push(p)
            }
            return
          }
          current.push(p)
        })
      } else {
        // 缺路网轨迹：把已连好的真实轨迹收成一段，之后从头开始 → 地图上是"断口"，不是直线
        if (current.length >= 2) runs.push(current)
        current = []
      }
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
    if (current.length >= 2) runs.push(current)
    const points = runs.flat()
    const orderNos = [...new Set(rows.map((r) => r.orderNo).filter(Boolean) as string[])]
    const distanceKm = sorted.reduce((sum, r) => sum + (Number(r.leg.distanceKm) || 0), 0)
    const driver = sorted.map((r) => r.leg).find((l) => l.driverName)?.driverName
    list.push({
      key: `leg-${plate}`,
      color: plateColorMap.value.get(plate) || '#909399',
      title: `${plate}${driver ? ` · ${driver}` : ''}`,
      orderNos,
      orderCount: orderNos.length,
      stops: [],
      locatedStops,
      driverText: driver || '',
      distanceKm,
      distanceText: distanceKm ? distanceKm.toFixed(1) : '-',
      points,
      polylines: runs,
      realSegments,
      totalSegments: legRows.length,
      legs: legRows,
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
      // 轨迹点：优先拼接后端真实道路分段（AMAP）；缺段的直接断开（不回退两点直线）
      const runs: { lng: number; lat: number }[][] = []
      let current: { lng: number; lat: number }[] = []
      let realSegments = 0
      let totalSegments = 0
      locatedStops.forEach((located, index) => {
        const here = { lng: located.lng, lat: located.lat }
        if (index === 0) {
          current.push(here)
          return
        }
        totalSegments++
        const key = `${plan.id}:${vehicleId}:${located.stop.visitSequence ?? 0}`
        const real = roadmapSegments.value.get(key)
        if (real && real.points.length >= 2) {
          realSegments++
          real.points.forEach((p, i) => {
            // 拼接处去掉与上段末点重复的起点
            if (i === 0) {
              const last = current[current.length - 1]
              if (!last || Math.abs(last.lng - p.lng) > 1e-9 || Math.abs(last.lat - p.lat) > 1e-9) current.push(p)
              return
            }
            current.push(p)
          })
        } else {
          // 缺真实道路数据：收尾当前折线，本段不画（地图上留断口）
          if (current.length >= 2) runs.push(current)
          current = []
        }
      })
      if (current.length >= 2) runs.push(current)
      const points = runs.flat()
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
        polylines: runs,
        realSegments,
        totalSegments
      })
    })
  })
  routes.value = list
}

// ==================== 地图（百度 BMapGL；坐标为 GCJ-02 → 上图前转 BD-09） ====================
/**
 * 该线路要画的连续折线：**只含真实道路轨迹**。
 * 缺路网数据的运输段不会进这里 → 地图上留断口，绝不出现"两点直连"的假轨迹。
 */
const routePolylines = (route: RouteView): { lng: number; lat: number }[][] => {
  const runs = (route.polylines ?? []).filter((run) => run.length >= 2)
  if (runs.length) return runs
  return route.points.length >= 2 ? [route.points] : []
}

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
    // 一段不间断的真实轨迹 = 一条实线；缺路网数据的段直接断开（不画直线、不画虚线）
    const routePaths = routePolylines(route).map((run) => run.map((p) => {
      const bd = gcj02ToBd09(p.lng, p.lat)
      return new BMapGL.Point(bd.lng, bd.lat)
    }))
    const path = routePaths.flat()
    if (!path.length) return
    routePaths.forEach((one) => {
      if (one.length < 2) return
      const polyline = new BMapGL.Polyline(one, {
        strokeColor: route.color,
        strokeWeight: 5,
        strokeOpacity: 0.9,
        strokeStyle: 'solid'
      })
      // 点线路 = 选中这台车（只显示它的线路与作业点，其余车辆自动隐藏）
      try {
        polyline.addEventListener('click', () => selectVehicleByKey(route.key))
      } catch (e) { /* 老版本 SDK 不支持事件绑定：不影响绘图 */ }
      map.addOverlay(polyline)
      overlays.value.push(polyline)
    })
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
  // 方向箭头只落在真实道路上（按下标的折线：缺路网数据的段没有箭头，避免指示不存在的轨迹）
  const runs = routePolylines(route)
  const runs2 = runs.map((points) => {
    const segs: number[] = []
    let total = 0
    for (let i = 1; i < points.length; i++) {
      const d = Math.hypot(points[i].lng - points[i - 1].lng, points[i].lat - points[i - 1].lat)
      segs.push(d)
      total += d
    }
    return { points, segs, total }
  }).filter((r) => r.total > 0)
  const total = runs2.reduce((sum, r) => sum + r.total, 0)
  if (total <= 0) return []
  const fractions = route.totalSegments > 1 ? [0.25, 0.6] : [0.5]
  const arrows: { point: { lng: number; lat: number }; angle: number }[] = []
  fractions.forEach((fraction) => {
    let target = total * fraction
    let rest = target
    for (const run of runs2) {
      if (rest > run.total) {
        rest -= run.total
        continue
      }
      for (let i = 0; i < run.segs.length; i++) {
        if (rest <= run.segs[i] || i === run.segs.length - 1) {
          const t = run.segs[i] === 0 ? 0 : rest / run.segs[i]
          const a = run.points[i]
          const b = run.points[i + 1]
          const lng = a.lng + (b.lng - a.lng) * t
          const lat = a.lat + (b.lat - a.lat) * t
          // 经纬度 → 屏幕方向：纬度向上、经度向右；角度自正北顺时针
          const dLng = (b.lng - a.lng) * Math.cos((lat * Math.PI) / 180)
          const dLat = b.lat - a.lat
          const angle = (Math.atan2(dLng, dLat) * 180) / Math.PI
          arrows.push({ point: { lng, lat }, angle })
          break
        }
        rest -= run.segs[i]
      }
      break
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
  const lines: { key: string; color: string; dots: { x: number; y: number; label: number }[]; points: string }[] = []
  mapRoutes.value.forEach((route) => {
    // 同一条线的多个"真实轨迹段"分别成折线：缺路网数据的段留断口，不画直线
    let seq = 0
    routePolylines(route).forEach((run, runIndex) => {
      const dots = run.map((p) => {
        seq += 1
        const pos = proj.project(p)
        return { x: pos.x, y: pos.y, label: seq }
      })
      if (dots.length < 2) return
      lines.push({
        key: `${route.key}#${runIndex}`,
        color: route.color,
        dots,
        points: dots.map((d) => `${d.x},${d.y}`).join(' ')
      })
    })
  })
  return lines
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
    // 默认只选中"第一套方案" = 一个任务段（一屏只看一段，路线才讲得清）。
    // 需要跨区对比时，用户再手动切到「全部方案（对比用）」或另一套方案（分屏看，不叠加）。
    activePlanId.value = plans.value.length && plans.value[0].id != null ? plans.value[0].id : 0
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
.viz-scope-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  b {
    color: var(--el-color-primary);
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
.viz-legend .legend-note {
  color: var(--el-text-color-secondary);
  font-size: 11px;
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
/* 行程链：同一台车的订单按先后串起来（演示主线） */
.journey-wrap {
  margin-bottom: 10px;
}
.journey-title {
  font-size: 12px;
  font-weight: 600;
  color: #123f6e;
  margin-bottom: 6px;
}
.journey-card {
  border: 1px solid #c7d8ea;
  background: #fff;
  border-radius: 8px;
  padding: 6px 10px 8px;
  margin-bottom: 8px;
  cursor: pointer;
  user-select: none;
}
.journey-card.picked {
  border-color: #1f5e9e;
  box-shadow: 0 0 0 1px #1f5e9e inset;
}
.journey-card.dimmed {
  opacity: 0.5;
}
.journey-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.journey-vehicle {
  font-weight: 600;
  color: #123f6e;
}
.journey-driver {
  color: var(--el-text-color-regular);
}
.journey-meta {
  color: var(--el-text-color-secondary);
  margin-left: auto;
}
.journey-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #1f5e9e;
  padding: 2px 0 4px;
}
.journey-arrow {
  color: var(--el-text-color-placeholder);
}
.journey-order {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 1px 0;
  color: var(--el-text-color-primary);
}
.journey-seq {
  color: var(--el-text-color-placeholder);
  width: 12px;
  text-align: right;
  flex-shrink: 0;
}
.journey-order-no {
  color: #1f5e9e;
  font-weight: 600;
  flex-shrink: 0;
}
.journey-order-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.journey-order-time {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.journey-handover {
  color: #e6a23c;
  font-weight: 600;
  flex-shrink: 0;
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
