<template>
  <ContentWrap title="运输拓扑（订单运输链可视化）">
    <ContentWrap>
      <el-form :inline="true" @submit.prevent="load">
        <el-form-item label="订单编号">
          <el-input-number v-model="orderId" :min="1" controls-position="right" placeholder="运输订单编号" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load"><Icon icon="ep:search" />查询</el-button>
        </el-form-item>
      </el-form>
      <div v-if="data" class="summary">
        <el-tag type="success">订单 {{ data.orderNo || data.orderId }}</el-tag>
        <el-tag>{{ data.orderStatusName }}</el-tag>
        <el-tag type="warning">{{ data.planningModeName || '—' }}</el-tag>
        <el-tag>总段数 {{ data.totalLegs ?? data.legs?.length ?? 0 }}</el-tag>
        <el-tag>换乘 {{ data.transferCount ?? 0 }} 次</el-tag>
        <el-tag v-if="data.totalDurationMinutes">预计 {{ data.totalDurationMinutes }} 分钟</el-tag>
      </div>
      <el-alert v-if="data && data.planReason" :title="'方案说明：' + data.planReason" type="info" :closable="false" style="margin-top: 12px" />
    </ContentWrap>

    <ContentWrap v-if="data && (data.candidates?.length ?? 0) > 0" title="候选方案对比（智能调度不是随机分配）">
      <el-table :data="data.candidates" stripe border>
        <el-table-column label="方案" align="center" width="140">
          <template #default="scope">
            <el-tag :type="scope.row.chosen ? 'success' : 'info'">
              {{ scope.row.modeName }}<template v-if="scope.row.chosen">（推荐）</template>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="段数" prop="legCount" align="center" width="80" />
        <el-table-column label="换乘次数" prop="transferCount" align="center" width="100" />
        <el-table-column label="里程(km)" prop="distanceKm" align="center" width="110" />
        <el-table-column label="预计(分钟)" prop="durationMinutes" align="center" width="120" />
        <el-table-column label="综合评分" prop="score" align="center" width="110" />
        <el-table-column label="理由" prop="reason" align="center" show-overflow-tooltip />
      </el-table>
    </ContentWrap>

    <ContentWrap v-if="data && (data.legs?.length ?? 0) > 0" title="运输链（图形化）">
      <div class="chain">
        <div class="node origin">
          <div class="n-title">📍 {{ data.originStationName || '起点' }}</div>
          <div class="n-sub">起点站</div>
          <div class="n-sub" v-if="data.orderNo">{{ data.orderNo }}</div>
        </div>
        <template v-for="(leg, i) in data.legs" :key="leg.id ?? i">
          <div class="arrow" :class="legClass(leg.status)">━━▶</div>
          <div class="node leg" :class="legClass(leg.status)">
            <div class="n-title">第 {{ leg.legSequence }} 段 · {{ leg.statusName }}</div>
            <div class="n-sub">🚚 {{ leg.driverName || '待分配' }} / {{ leg.plateNo || '待派车' }}</div>
            <div class="n-sub">{{ leg.fromStationName }} → {{ leg.toStationName }}</div>
            <div class="n-sub">
              {{ leg.distanceKm ?? '—' }}km · {{ leg.durationMinutes ?? '—' }}min<template v-if="leg.navigationSource === 'ESTIMATED'"> · 估算</template>
            </div>
          </div>
          <template v-if="handoverAfter(leg)">
            <div class="arrow hub">⇄</div>
            <div class="node hub" :class="handoverClass(handoverAfter(leg)!.status)">
              <div class="n-title">🔄 换乘交接 · {{ handoverAfter(leg)!.statusName }}</div>
              <div class="n-sub">换乘站：{{ handoverAfter(leg)!.stationName }}</div>
              <div class="n-sub">
                {{ handoverAfter(leg)!.fromDriverName || '—' }} → {{ handoverAfter(leg)!.toDriverName || '待接' }}
              </div>
            </div>
          </template>
        </template>
        <div class="arrow">━━▶</div>
        <div class="node dest">
          <div class="n-title">🏁 {{ data.destinationStationName || '终点' }}</div>
          <div class="n-sub">目的站</div>
        </div>
      </div>
    </ContentWrap>

    <ContentWrap v-if="data" title="运输段">
      <el-table :data="data.legs || []" stripe border>
        <el-table-column label="段" prop="legSequence" align="center" width="70" />
        <el-table-column label="起点" prop="fromStationName" align="center" />
        <el-table-column label="终点" prop="toStationName" align="center" />
        <el-table-column label="司机" prop="driverName" align="center" width="110" />
        <el-table-column label="车辆" prop="plateNo" align="center" width="140" />
        <el-table-column label="状态" prop="statusName" align="center" width="120" />
        <el-table-column label="里程(km)" prop="distanceKm" align="center" width="100" />
        <el-table-column label="预计到达" prop="estimatedArrival" align="center" width="170" />
        <el-table-column label="导航来源" prop="navigationSource" align="center" width="120" />
      </el-table>
    </ContentWrap>

    <ContentWrap v-if="data && (data.handovers?.length ?? 0) > 0" title="换乘交接">
      <el-table :data="data.handovers" stripe border>
        <el-table-column label="换乘站" prop="stationName" align="center" />
        <el-table-column label="交出司机" prop="fromDriverName" align="center" width="120" />
        <el-table-column label="接收司机" prop="toDriverName" align="center" width="120" />
        <el-table-column label="交出车辆" prop="fromPlateNo" align="center" width="140" />
        <el-table-column label="接收车辆" prop="toPlateNo" align="center" width="140" />
        <el-table-column label="件数" prop="itemCount" align="center" width="80" />
        <el-table-column label="状态" prop="statusName" align="center" width="120" />
        <el-table-column label="完成时间" prop="handoverCompletedAt" align="center" width="170" />
      </el-table>
    </ContentWrap>

    <ContentWrap v-if="data && (data.timeline?.length ?? 0) > 0" title="事件时间线">
      <el-timeline>
        <el-timeline-item v-for="(t, i) in data.timeline" :key="i" :timestamp="t.eventTime" placement="top">
          <b>{{ t.eventTypeName || t.eventType }}</b> — {{ t.detail }}
        </el-timeline-item>
      </el-timeline>
    </ContentWrap>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as TopologyApi from '@/api/transport/topology'

defineOptions({ name: 'TransportTopology' })

const message = useMessage()
const route = useRoute()
const orderId = ref<number | undefined>(undefined)
const data = ref<TopologyApi.OrderTopologyVO | null>(null)

/** 段状态配色：已完成=绿 / 进行中=橙 / 未开始=灰 / 异常=红 */
const legClass = (status?: number) => {
  if (status === 11) return 'done'
  if (status === 99) return 'exception'
  if (status != null && status >= 7 && status <= 10) return 'doing'
  return 'todo'
}
const handoverClass = (status?: number) => {
  if (status === 4) return 'done'
  if (status === 7) return 'exception'
  if (status === 0 || status === 5) return 'todo'
  return 'doing'
}
/** 换乘交接挂在"来源段目的站"之后（交接站点 = 来源段目的站） */
const handoverAfter = (leg: TopologyApi.TopologyLeg) =>
  (data.value?.handovers || []).find((h) => h.stationName && h.stationName === leg.toStationName)

const load = async () => {
  if (!orderId.value) {
    message.warning('请输入订单编号')
    return
  }
  data.value = await TopologyApi.getTopologyByOrder(orderId.value)
}

onMounted(() => {
  // 支持从调度中心带单号直接打开
  const q = Number(route.query.orderId)
  if (q) {
    orderId.value = q
    load()
  }
})
</script>

<style scoped>
.summary {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}

/* ==================== 运输链（图形化） ==================== */
.chain {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
}

.node {
  min-width: 180px;
  max-width: 260px;
  border-radius: 10px;
  padding: 10px 12px;
  border: 2px solid #dcdfe6;
  background: #fafafa;
}

.node .n-title {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}

.node .n-sub {
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
}

.node.origin { border-color: #2e7d32; background: #f1f8f2; }
.node.dest { border-color: #1565c0; background: #f0f6fc; }
.node.done { border-color: #67c23a; background: #f0f9eb; }
.node.doing { border-color: #e6a23c; background: #fdf6ec; }
.node.todo { border-color: #c0c4cc; background: #fafafa; }
.node.exception { border-color: #f56c6c; background: #fef0f0; }
.node.hub { border-style: dashed; border-color: #c75b2a; background: #fff7f0; }
.node.hub.done { border-style: solid; border-color: #67c23a; background: #f0f9eb; }

.arrow {
  display: flex;
  align-items: center;
  color: #c0c4cc;
  font-size: 14px;
}

.arrow.done { color: #67c23a; }
.arrow.doing { color: #e6a23c; }
.arrow.exception { color: #f56c6c; }
.arrow.hub { color: #c75b2a; }
</style>
