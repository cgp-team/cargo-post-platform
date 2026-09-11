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
const orderId = ref<number | undefined>(undefined)
const data = ref<TopologyApi.OrderTopologyVO | null>(null)

const load = async () => {
  if (!orderId.value) {
    message.warning('请输入订单编号')
    return
  }
  data.value = await TopologyApi.getTopologyByOrder(orderId.value)
}
</script>

<style scoped>
.summary {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
}
</style>
