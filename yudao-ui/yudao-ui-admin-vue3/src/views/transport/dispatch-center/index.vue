<template>
  <ContentWrap title="调度中心（订单池 · 实时地图 · 运输详情 · 事件时间线）">
    <el-row :gutter="12">
      <!-- ① 订单池 -->
      <el-col :xs="24" :sm="12" :md="8" :lg="6">
        <ContentWrap title="订单池">
          <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
            <el-button size="small" @click="loadPool"><Icon icon="ep:refresh" />刷新</el-button>
          </div>
          <el-table
            :data="pool"
            highlight-current-row
            style="margin-top:8px"
            height="560"
            size="small"
            @current-change="onSelectOrder"
          >
            <el-table-column label="订单号" prop="orderNo" align="center" show-overflow-tooltip />
            <el-table-column label="状态" align="center" width="90">
              <template #default="scope">{{ orderStatusText(scope.row.status) }}</template>
            </el-table-column>
          </el-table>
          <Pagination
            v-model:page="poolQuery.pageNo"
            v-model:limit="poolQuery.pageSize"
            :total="poolTotal"
            :pager-count="5"
            layout="total, prev, pager, next"
            @pagination="loadPool"
          />
        </ContentWrap>
      </el-col>

      <!-- ② 实时地图 + ③ 运输详情 -->
      <el-col :xs="24" :sm="12" :md="16" :lg="11">
        <ContentWrap title="实时地图（站点 / 车辆 / 选中订单运输链）">
          <div ref="mapRef" style="width:100%;height:300px"></div>
          <div v-if="!mapReady" style="color:var(--el-text-color-secondary);font-size:12px;margin-top:6px">
            地图未就绪（需后台配置百度地图 Key）；下方运输详情不受影响
          </div>
          <div class="map-legend">
            <span><i class="dot station"></i>站点</span>
            <span><i class="dot vehicle"></i>车辆</span>
            <span><i class="line"></i>运输段</span>
            <span><i class="line dashed"></i>估算段</span>
            <span><i class="dot hub"></i>换乘站</span>
          </div>
        </ContentWrap>
        <ContentWrap title="运输详情">
          <el-empty v-if="!topology" description="点击左侧订单池中的订单查看运输链" />
          <template v-else>
            <div class="summary">
              <el-tag>订单 {{ topology.orderNo || topology.orderId }}</el-tag>
              <el-tag type="warning">{{ topology.planningModeName || '—' }}</el-tag>
              <el-tag>段数 {{ topology.totalLegs ?? topology.legs?.length ?? 0 }}</el-tag>
              <el-tag>换乘 {{ topology.transferCount ?? 0 }}</el-tag>
              <el-tag v-if="topology.totalDurationMinutes">预计 {{ topology.totalDurationMinutes }} 分钟</el-tag>
              <el-button link type="primary" @click="openTopology">查看图形化运输链 ›</el-button>
            </div>
            <el-alert
              v-if="topology.planReason"
              :title="'方案说明：' + topology.planReason"
              type="info"
              :closable="false"
              style="margin:8px 0"
            />
            <el-table :data="topology.legs || []" size="small" border>
              <el-table-column label="段" prop="legSequence" align="center" width="50" />
              <el-table-column label="路线" align="center">
                <template #default="scope">
                  {{ scope.row.fromStationName }} → {{ scope.row.toStationName }}
                </template>
              </el-table-column>
              <el-table-column label="司机" prop="driverName" align="center" width="90" />
              <el-table-column label="车辆" prop="plateNo" align="center" width="120" />
              <el-table-column label="状态" prop="statusName" align="center" width="100" />
              <el-table-column label="操作" align="center" width="110">
                <template #default="scope">
                  <el-button
                    v-if="scope.row.status === 99"
                    link
                    type="warning"
                    v-hasPermi="['transport:topology:query']"
                    @click="onReplan(scope.row)"
                  >重调度</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </ContentWrap>
      </el-col>

      <!-- ④ 事件时间线 -->
      <el-col :xs="24" :sm="24" :md="24" :lg="7">
        <ContentWrap title="事件时间线">
          <el-empty v-if="!topology || !(topology.timeline || []).length" description="暂无事件" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="(t, i) in topology.timeline"
              :key="i"
              :timestamp="t.eventTime"
              placement="top"
            >
              <b>{{ t.eventTypeName || t.eventType }}</b>
              <div style="color:var(--el-text-color-regular);font-size:12px">{{ t.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </ContentWrap>
      </el-col>
    </el-row>
  </ContentWrap>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import * as DispatchApi from '@/api/transport/dispatch'
import * as TopologyApi from '@/api/transport/topology'
import * as MonitoringApi from '@/api/transport/monitoring'
import {
  loadBaiduMapSdk,
  gcj02ToBd09,
  clusterByGrid,
  shouldCluster,
  clusterBubbleStyle
} from '@/components/Map/src/utils'
// 订单状态文案统一走共享常量（原先本页自备的 ORDER_STATUS 缺 6 待审核/7 待客户操作，显示"—"）
import { ORDER_STATUS_LABELS } from '../constants'

defineOptions({ name: 'TransportDispatchCenter' })

const message = useMessage()
const router = useRouter()
const pool = ref<any[]>([])
const topology = ref<TopologyApi.OrderTopologyVO | null>(null)
const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
let map: any = null
/** 基础图层（站点 + 车辆）覆盖物，卸载时统一清理 */
let baseOverlays: any[] = []

/** 车辆线路配色（与调度可视化弹窗同一套颜色，按车牌稳定分配） */
const LEG_COLORS = [
  'var(--brand-primary)', 'var(--el-color-warning)', 'var(--palette-1)', 'var(--palette-2)', 'var(--palette-3)', 'var(--palette-4)',
  'var(--palette-5)', 'var(--palette-6)', 'var(--palette-7)', 'var(--palette-8)', 'var(--palette-9)', 'var(--palette-10)'
]

const orderStatusText = (s?: number) => (s == null ? '—' : ORDER_STATUS_LABELS[s] || '—')

// WEB-20: 订单池改为真分页（此前 pageNo=1/pageSize=100 写死，超出 100 条不可见）
const poolQuery = reactive({ pageNo: 1, pageSize: 20 })
const poolTotal = ref(0)
const loadPool = async () => {
  const res = await DispatchApi.getDispatchPoolPage(poolQuery)
  pool.value = res.list || []
  poolTotal.value = Number(res.total) || 0
}

/** 当前订单运输链的覆盖物：切换订单时逐条移除，避免新旧链路叠加 */
const routeOverlays: any[] = []
const clearRouteOverlays = () => {
  if (!map) {
    routeOverlays.length = 0
    return
  }
  routeOverlays.forEach((o) => {
    try { map.removeOverlay(o) } catch (e) { /* ignore */ }
  })
  routeOverlays.length = 0
}

const renderTopology = () => {
  if (!map || !topology.value) return
  const BMapGL = (window as any).BMapGL
  // 切换订单时必须先清掉上一条运输链的覆盖物，否则新旧链路叠在一起（"点另一个订单前一个不消失"）
  clearRouteOverlays()
  // 站点/轨迹是 GCJ-02，百度底图是 BD-09：不转换会整体偏移数百米（"站点标不准"就是这个原因）
  const pt = (lng: number, lat: number) => {
    const bd = gcj02ToBd09(lng, lat)
    return new BMapGL.Point(bd.lng, bd.lat)
  }
  const legs = topology.value.legs || []
  const points: any[] = []
  // 同一台车一个颜色（按车牌稳定排序分配，与可视化弹窗的车辆配色规则一致）：
  // 联运换乘时一眼能看出"这几段是同一台车、那几段换了另一台车"。
  const plates = [...new Set(legs.map((l) => l.plateNo).filter(Boolean))].sort()
  const colorOf = (plate?: string) =>
    plate ? LEG_COLORS[plates.indexOf(plate) % LEG_COLORS.length] : LEG_COLORS[0]
  legs.forEach((l) => {
    if (l.fromLongitude == null || l.toLongitude == null) return
    // 只画真实道路轨迹；缺轨迹（高德不可用/估算）的段不画"两点直线"——
    // 缺轨迹时画"两站直连"既不好看也不可靠，宁可断口。
    const road = (l.navigationPolyline || []).map((p) => pt(p.longitude, p.latitude))
    const p1 = pt(l.fromLongitude, l.fromLatitude!)
    const p2 = pt(l.toLongitude, l.toLatitude!)
    points.push(p1, p2)
    if (road.length >= 2) {
      points.push(...road)
      const line = new BMapGL.Polyline(road, {
        // 真实道路实线（按车辆配色）
        strokeColor: colorOf(l.plateNo),
        strokeWeight: 5,
        strokeStyle: 'solid'
      })
      map.addOverlay(line)
      routeOverlays.push(line)
    }
    if (l.handoverRequired) {
      const marker = new BMapGL.Marker(p2)
      map.addOverlay(marker)
      routeOverlays.push(marker)
      const label = new BMapGL.Label('换乘站 ' + (l.toStationName || ''), {
        position: p2, offset: new BMapGL.Size(10, -30)
      })
      map.addOverlay(label)
      routeOverlays.push(label)
    }
  })
  if (points.length) map.setViewport(points)
}

const onSelectOrder = async (row: any) => {
  if (!row || !row.id) return
  topology.value = await TopologyApi.getTopologyByOrder(row.id)
  renderTopology()
}

/** 跳转到"运输拓扑"页看图形化运输链（带单号直接打开） */
const openTopology = () => {
  if (!topology.value?.orderId) return
  router.push({ path: '/transport/topology', query: { orderId: topology.value.orderId } })
}

const onReplan = async (leg: any) => {
  try {
    await TopologyApi.replanLeg(leg.id, '调度中心手工重调度')
    message.success('重调度成功')
    const row = pool.value.find((o) => o.id === topology.value?.orderId)
    if (row) await onSelectOrder(row)
  } catch (e) {
    message.error('重调度失败：当前无可调度车辆/司机')
  }
}

const initMap = async () => {
  try {
    await loadBaiduMapSdk(15000)
    const BMapGL = (window as any).BMapGL
    if (!BMapGL || !mapRef.value) return
    map = new BMapGL.Map(mapRef.value)
    map.centerAndZoom(new BMapGL.Point(106.5765, 29.5325), 12)
    map.enableScrollWheelZoom(true)
    const data = await MonitoringApi.getMonitoringMapData()
    // 站点/线路来自地图数据；车辆走独立接口（MonitoringMapDataVO 不含 vehicles）
    const vehicles = await MonitoringApi.getMonitoringVehicles().catch(() => [])
    renderBaseOverlays(data?.stations || [], vehicles || [])
    mapReady.value = true
    renderTopology()
  } catch (e) {
    mapReady.value = false
  }
}

/**
 * 站点 + 车辆基础图层（WEB-14 / SEP-05）。
 * 点位总数超过阈值时按屏幕像素网格聚合（同格多点合成数量气泡），
 * 避免一次性叠加数百个 Circle + Label 阻塞渲染；未超阈值时逐点渲染，标签可读性更好。
 */
const renderBaseOverlays = (stations: any[], vehicleList: any[]) => {
  const BMapGL = (window as any).BMapGL
  const toBd = (lng: number, lat: number) => gcj02ToBd09(lng, lat)
  const stationPoints = stations
    .filter((s) => s.longitude != null && s.latitude != null)
    .map((s) => {
      const bd = toBd(s.longitude, s.latitude)
      return { lng: bd.lng, lat: bd.lat, name: s.stationName }
    })
  const vehiclePoints = vehicleList
    .filter((v) => v.longitude != null && v.latitude != null)
    .map((v) => {
      const bd = toBd(v.longitude, v.latitude)
      return { lng: bd.lng, lat: bd.lat, name: v.plateNo || '运输车辆' }
    })

  const paint = <T extends { lng: number; lat: number }>(
    points: T[],
    draw: (item: T, point: any) => void
  ) => {
    if (!shouldCluster(points.length)) {
      points.forEach((p) => draw(p, new BMapGL.Point(p.lng, p.lat)))
      return
    }
    clusterByGrid(points, (p) => {
      const px = map.pointToPixel(new BMapGL.Point(p.lng, p.lat))
      return { x: px.x, y: px.y }
    }).forEach((bucket) => {
      const point = new BMapGL.Point(bucket.lng, bucket.lat)
      if (bucket.items.length === 1) {
        draw(bucket.items[0], point)
        return
      }
      const bubble = new BMapGL.Label(String(bucket.items.length), {
        position: point,
        offset: new BMapGL.Size(-18, -18)
      })
      bubble.setStyle(clusterBubbleStyle(bucket.items.length))
      bubble.addEventListener?.('click', () => map.setZoom(Math.min(map.getZoom() + 2, 19)))
      map.addOverlay(bubble)
      baseOverlays.push(bubble)
    })
  }

  paint(stationPoints, (s, point) => {
    const circle = new BMapGL.Circle(point, 60, {
      strokeColor: 'var(--brand-primary)',
      fillColor: 'var(--brand-primary)',
      fillOpacity: 0.3
    })
    const label = new BMapGL.Label(s.name, { position: point, offset: new BMapGL.Size(6, -28) })
    map.addOverlay(circle)
    map.addOverlay(label)
    baseOverlays.push(circle, label)
  })

  paint(vehiclePoints, (v, point) => {
    const marker = new BMapGL.Marker(point)
    const label = new BMapGL.Label(v.name, { position: point, offset: new BMapGL.Size(-20, -30) })
    map.addOverlay(marker)
    map.addOverlay(label)
    baseOverlays.push(marker, label)
  })
}

onMounted(async () => {
  await loadPool()
  initMap()
})

onUnmounted(() => {
  baseOverlays = []
  routeOverlays.length = 0
  if (map) {
    map.destroy()
    map = null
  }
})
</script>

<style scoped>
.summary {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.map-legend {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.map-legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.map-legend .dot.station { background: var(--brand-primary); }
.map-legend .dot.vehicle { background: var(--brand-accent); }
.map-legend .dot.hub { background: var(--brand-primary-dark); }

.map-legend .line {
  display: inline-block;
  width: 14px;
  height: 3px;
  background: var(--brand-primary);
  margin-right: 4px;
  vertical-align: middle;
}

.map-legend .line.dashed {
  background: repeating-linear-gradient(90deg, var(--brand-accent) 0 4px, transparent 4px 7px);
}
</style>
