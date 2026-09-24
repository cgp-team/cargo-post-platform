<template>
  <ContentWrap title="调度中心（订单池 · 实时地图 · 运输详情 · 事件时间线）">
    <el-row :gutter="12">
      <!-- ① 订单池 -->
      <el-col :span="6">
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
        </ContentWrap>
      </el-col>

      <!-- ② 实时地图 + ③ 运输详情 -->
      <el-col :span="11">
        <ContentWrap title="实时地图（站点 / 车辆 / 选中订单运输链）">
          <div ref="mapRef" style="width:100%;height:300px"></div>
          <div v-if="!mapReady" style="color:#909399;font-size:12px;margin-top:6px">
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
      <el-col :span="7">
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
              <div style="color:#606266;font-size:12px">{{ t.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </ContentWrap>
      </el-col>
    </el-row>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as DispatchApi from '@/api/transport/dispatch'
import * as TopologyApi from '@/api/transport/topology'
import * as MonitoringApi from '@/api/transport/monitoring'
import { loadBaiduMapSdk, gcj02ToBd09 } from '@/components/Map/src/utils'
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

/** 车辆线路配色（与调度可视化弹窗同一套颜色，按车牌稳定分配） */
const LEG_COLORS = [
  '#1F5E9E', '#E6A23C', '#2E9E6B', '#D9534F', '#7B5BD6', '#0FA3B1',
  '#D4801A', '#C2185B', '#4A7C1F', '#5A6ACF', '#8D6E63', '#00838F'
]

const orderStatusText = (s?: number) => (s == null ? '—' : ORDER_STATUS_LABELS[s] || '—')

const loadPool = async () => {
  const res = await DispatchApi.getDispatchPoolPage({ pageNo: 1, pageSize: 100 })
  pool.value = res.list || []
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
    const toPoint = (lng: number, lat: number) => {
      const bd = gcj02ToBd09(lng, lat)
      return new BMapGL.Point(bd.lng, bd.lat)
    }
    ;(data?.stations || []).forEach((s: any) => {
      if (s.longitude == null) return
      const p = toPoint(s.longitude, s.latitude)
      map.addOverlay(new BMapGL.Circle(p, 60, { strokeColor: '#1F5E9E', fillColor: '#1F5E9E', fillOpacity: 0.3 }))
      map.addOverlay(new BMapGL.Label(s.stationName, { position: p, offset: new BMapGL.Size(6, -28) }))
    })
    ;(vehicles || []).forEach((v: any) => {
      if (v.longitude == null) return
      const p = toPoint(v.longitude, v.latitude)
      map.addOverlay(new BMapGL.Marker(p))
      map.addOverlay(new BMapGL.Label(v.plateNo || '运输车辆', { position: p, offset: new BMapGL.Size(-20, -30) }))
    })
    mapReady.value = true
    renderTopology()
  } catch (e) {
    mapReady.value = false
  }
}

onMounted(async () => {
  await loadPool()
  initMap()
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
  color: #606266;
}

.map-legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: middle;
}

.map-legend .dot.station { background: #1f5e9e; }
.map-legend .dot.vehicle { background: #2e7bbf; }
.map-legend .dot.hub { background: #123f6e; }

.map-legend .line {
  display: inline-block;
  width: 14px;
  height: 3px;
  background: #1f5e9e;
  margin-right: 4px;
  vertical-align: middle;
}

.map-legend .line.dashed {
  background: repeating-linear-gradient(90deg, #2e7bbf 0 4px, transparent 4px 7px);
}
</style>
