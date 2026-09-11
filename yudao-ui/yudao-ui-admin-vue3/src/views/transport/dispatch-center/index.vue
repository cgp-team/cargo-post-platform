<template>
  <ContentWrap title="调度中心（订单池 · 实时地图 · 运输详情 · 事件时间线）">
    <el-row :gutter="12">
      <!-- ① 订单池 -->
      <el-col :span="6">
        <ContentWrap title="订单池">
          <el-button size="small" @click="loadPool"><Icon icon="ep:refresh" />刷新</el-button>
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
import { loadBaiduMapSdk } from '@/components/Map/src/utils'

defineOptions({ name: 'TransportDispatchCenter' })

const message = useMessage()
const pool = ref<any[]>([])
const topology = ref<TopologyApi.OrderTopologyVO | null>(null)
const mapRef = ref<HTMLDivElement>()
const mapReady = ref(false)
let map: any = null

const ORDER_STATUS: Record<number, string> = {
  0: '已创建', 1: '已入池', 2: '已分配', 3: '已发车', 4: '已完成', 5: '已取消',
  8: '待入池', 9: '部分完成', 10: '运输中', 11: '换乘中', 12: '派送中', 13: '异常'
}
const orderStatusText = (s?: number) => (s == null ? '—' : ORDER_STATUS[s] || '—')

const loadPool = async () => {
  const res = await DispatchApi.getDispatchPoolPage({ pageNo: 1, pageSize: 100 })
  pool.value = res.list || []
}

const renderTopology = () => {
  if (!map || !topology.value) return
  const BMapGL = (window as any).BMapGL
  const legs = topology.value.legs || []
  const points: any[] = []
  legs.forEach((l) => {
    if (l.fromLongitude == null || l.toLongitude == null) return
    const p1 = new BMapGL.Point(l.fromLongitude, l.fromLatitude)
    const p2 = new BMapGL.Point(l.toLongitude, l.toLatitude)
    points.push(p1, p2)
    map.addOverlay(new BMapGL.Polyline([p1, p2], { strokeColor: '#2E7D32', strokeWeight: 5 }))
    if (l.handoverRequired) {
      map.addOverlay(new BMapGL.Marker(p2))
      map.addOverlay(new BMapGL.Label('换乘站 ' + (l.toStationName || ''), {
        position: p2, offset: new BMapGL.Size(10, -30)
      }))
    }
  })
  if (points.length) map.setViewport(points)
}

const onSelectOrder = async (row: any) => {
  if (!row || !row.id) return
  topology.value = await TopologyApi.getTopologyByOrder(row.id)
  renderTopology()
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
    ;(data?.stations || []).forEach((s: any) => {
      if (s.longitude == null) return
      const p = new BMapGL.Point(s.longitude, s.latitude)
      map.addOverlay(new BMapGL.Circle(p, 60, { strokeColor: '#1565C0', fillColor: '#1565C0', fillOpacity: 0.3 }))
      map.addOverlay(new BMapGL.Label(s.stationName, { position: p, offset: new BMapGL.Size(6, -28) }))
    })
    ;(data?.vehicles || []).forEach((v: any) => {
      if (v.longitude == null) return
      const p = new BMapGL.Point(v.longitude, v.latitude)
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
</style>
