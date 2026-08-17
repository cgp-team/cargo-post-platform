<template>
  <ContentWrap title="运营概览">
    <!-- 资源统计卡片 -->
    <el-row :gutter="16">
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">车辆总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.vehicleCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:van" :size="40" color="#2E7D32" />
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">司机总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.driverCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:user" :size="40" color="#4CAF50" />
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">站点总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.stationCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:location" :size="40" color="#D9A441" />
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">线路总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.routeCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:guide" :size="40" color="#C75B2A" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 运营摘要卡片 -->
    <el-row :gutter="16">
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="text-gray-500 text-sm">今日订单</div>
          <div class="text-3xl font-bold mt-2">{{ summary.orderToday ?? '-' }}</div>
          <div class="text-xs text-gray-400 mt-2">累计 {{ summary.orderTotal ?? 0 }} 单</div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="text-gray-500 text-sm">今日营收(元)</div>
          <div class="text-3xl font-bold mt-2">{{ fmtAmount(summary.orderAmountToday) }}</div>
          <div class="text-xs text-gray-400 mt-2">累计 {{ fmtAmount(summary.orderAmountTotal) }} 元</div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="text-gray-500 text-sm">在途车辆</div>
          <div class="text-3xl font-bold mt-2 text-green-500">{{ summary.vehicleInTransit ?? 0 }}</div>
          <div class="text-xs text-gray-400 mt-2">
            空闲 {{ summary.vehicleIdle ?? 0 }} · 停用 {{ summary.vehicleDisabled ?? 0 }}
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="text-gray-500 text-sm">执行中班次</div>
          <div class="text-3xl font-bold mt-2 text-blue-500">{{ summary.shiftInTransit ?? 0 }}</div>
          <div class="text-xs text-gray-400 mt-2">
            未发 {{ summary.shiftPending ?? 0 }} · 已完成 {{ summary.shiftCompleted ?? 0 }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 返程结算（运营报表） -->
    <el-card shadow="never" class="stat-card">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="font-700">返程结算 · 运营报表</span>
          <div>
            <el-date-picker
              v-model="settleRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              value-format="YYYY-MM-DD HH:mm:ss"
              style="width: 340px"
            />
            <el-button type="primary" class="ml-8px" :loading="settleLoading" @click="loadSettlement">查询</el-button>
          </div>
        </div>
      </template>
      <template v-if="settlement">
        <el-row :gutter="16">
          <el-col :md="6" :sm="12" :xs="24">
            <div class="settle-item">
              <div class="text-gray-500 text-sm">总行驶里程(km)</div>
              <div class="text-2xl font-bold mt-1">{{ Number(settlement.totalDistance ?? 0).toFixed(2) }}</div>
            </div>
          </el-col>
          <el-col :md="6" :sm="12" :xs="24">
            <div class="settle-item">
              <div class="text-gray-500 text-sm">服务乘客</div>
              <div class="text-2xl font-bold mt-1">{{ settlement.passengerCount ?? 0 }} 人</div>
            </div>
          </el-col>
          <el-col :md="6" :sm="12" :xs="24">
            <div class="settle-item">
              <div class="text-gray-500 text-sm">包裹收发量</div>
              <div class="text-2xl font-bold mt-1">{{ settlement.parcelCount ?? 0 }} 件</div>
            </div>
          </el-col>
          <el-col :md="6" :sm="12" :xs="24">
            <div class="settle-item">
              <div class="text-gray-500 text-sm">乘客平均等待</div>
              <div class="text-2xl font-bold mt-1">{{ settlement.avgPassengerWaitMinutes ?? '-' }} 分钟</div>
            </div>
          </el-col>
        </el-row>
        <el-table
          v-if="settlement.perVehicle?.length"
          :data="settlement.perVehicle"
          size="small"
          border
          style="margin-top:12px"
        >
          <el-table-column label="车牌" prop="plateNo" align="center" />
          <el-table-column label="承运方案数" prop="runCount" align="center" />
          <el-table-column label="载客总数" prop="passengerCount" align="center" />
          <el-table-column label="包裹收发量" prop="parcelCount" align="center" />
        </el-table>
        <el-empty v-else :image-size="80" description="该区间暂无已完成方案" />
      </template>
    </el-card>

    <!-- 图表区 -->
    <el-row :gutter="16" class="mt-16px">
      <el-col :md="12" :xs="24">
        <el-card shadow="never" class="stat-card">
          <template #header><span class="font-700">订单类型分布</span></template>
          <Echart :height="280" :options="typeChartOptions" />
        </el-card>
      </el-col>
      <el-col :md="12" :xs="24">
        <el-card shadow="never" class="stat-card">
          <template #header><span class="font-700">车辆状态分布</span></template>
          <Echart :height="280" :options="vehicleChartOptions" />
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="16">
      <el-col :span="24">
        <el-card shadow="never" class="stat-card">
          <template #header><span class="font-700">近7日订单与营收趋势</span></template>
          <Echart :height="320" :options="trendChartOptions" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <el-row :gutter="16" class="mt-16px">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header>
            <span class="font-700">快捷入口</span>
          </template>
          <el-row :gutter="12">
            <el-col :md="4" :sm="8" :xs="12" v-for="item in quickLinks" :key="item.label" class="mb-12px">
              <el-button class="w-full" @click="$router.push(item.path)">
                <Icon :icon="item.icon" class="mr-4px" />
                {{ item.label }}
              </el-button>
            </el-col>
          </el-row>
        </el-card>
      </el-col>
    </el-row>
  </ContentWrap>
</template>

<script setup lang="ts">
import type { EChartsOption } from 'echarts'
import { getDashboardStatistics } from '@/api/transport'
import {
  getDashboardSummary,
  getOrderStatistics,
  type DashboardSummaryVO,
  type OrderStatisticsVO
} from '@/api/transport/dashboard'
import { getDispatchSettlement, type DispatchSettlementRespVO } from '@/api/transport/dispatch'
import { formatDate } from '@/utils/formatTime'

defineOptions({ name: 'TransportDashboard' })

const ORDER_TYPE_LABELS: Record<number, string> = { 1: '客运', 2: '货运', 3: '邮快件' }

const loading = ref(true)
const stats = ref<Record<string, number>>({})
const summary = ref<Partial<DashboardSummaryVO>>({})
const orderStats = ref<OrderStatisticsVO>({ typeDistribution: [], statusDistribution: [], dailyTrend: [] })

// 返程结算
const settleRange = ref<[string, string] | null>(['', ''])
const settleLoading = ref(false)
const settlement = ref<DispatchSettlementRespVO>()
const loadSettlement = async () => {
  if (!settleRange.value?.[0] || !settleRange.value?.[1]) {
    useMessage().warning('请选择结算时间区间')
    return
  }
  settleLoading.value = true
  try {
    settlement.value = await getDispatchSettlement({
      batchStart: settleRange.value[0],
      batchEnd: settleRange.value[1]
    })
  } finally {
    settleLoading.value = false
  }
}
/** 默认结算区间 = 今天 00:00 ~ 当前 */
const initSettleRange = () => {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0)
  settleRange.value = [formatDate(start), formatDate(now)]
}

const quickLinks = [
  { label: '实时监控', path: '/transport/monitoring/map', icon: 'ep:map-location' },
  { label: '站点管理', path: '/transport/station', icon: 'ep:location' },
  { label: '车辆管理', path: '/transport/vehicle', icon: 'ep:van' },
  { label: '线路管理', path: '/transport/route', icon: 'ep:guide' },
  { label: '司机管理', path: '/transport/driver', icon: 'ep:user' },
  { label: '班次管理', path: '/transport/shift', icon: 'ep:clock' },
  { label: '订单管理', path: '/transport/order', icon: 'ep:document' }
]

const fmtAmount = (value?: number) => Number(value ?? 0).toFixed(2)

/** 订单类型分布饼图 */
const typeChartOptions = computed<EChartsOption>(() => ({
  color: ['#2E7D32', '#D9A441', '#4CAF50', '#86BB88'],
  tooltip: { trigger: 'item', formatter: '{b}: {c} 单 ({d}%)' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['35%', '65%'],
      center: ['50%', '45%'],
      label: { formatter: '{b}\n{c} 单' },
      data: orderStats.value.typeDistribution.map((item) => ({
        name: ORDER_TYPE_LABELS[item.type] || `类型${item.type}`,
        value: item.count
      }))
    }
  ]
}))

/** 车辆状态分布环图 */
const vehicleChartOptions = computed<EChartsOption>(() => ({
  color: ['#4CAF50', '#2E7D32', '#86BB88'],
  tooltip: { trigger: 'item', formatter: '{b}: {c} 辆 ({d}%)' },
  legend: { bottom: 0 },
  series: [
    {
      type: 'pie',
      radius: ['45%', '70%'],
      center: ['50%', '45%'],
      label: { formatter: '{b}\n{c} 辆' },
      data: [
        { name: '在途', value: summary.value.vehicleInTransit ?? 0 },
        { name: '空闲', value: summary.value.vehicleIdle ?? 0 },
        { name: '停用', value: summary.value.vehicleDisabled ?? 0 }
      ]
    }
  ]
}))

/** 近7日订单量(柱)与营收(线)双轴趋势 */
const trendChartOptions = computed<EChartsOption>(() => ({
  color: ['#2E7D32', '#C75B2A'],
  tooltip: { trigger: 'axis' },
  legend: { top: 0, data: ['订单量', '营收(元)'] },
  grid: { top: 40, left: 24, right: 24, bottom: 24, containLabel: true },
  xAxis: {
    type: 'category',
    data: orderStats.value.dailyTrend.map((item) => item.date.slice(5))
  },
  yAxis: [
    { type: 'value', name: '订单量', minInterval: 1 },
    { type: 'value', name: '营收(元)', splitLine: { show: false } }
  ],
  series: [
    {
      name: '订单量',
      type: 'bar',
      barMaxWidth: 24,
      data: orderStats.value.dailyTrend.map((item) => item.count)
    },
    {
      name: '营收(元)',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      data: orderStats.value.dailyTrend.map((item) => Number(item.amount ?? 0))
    }
  ]
}))

const loadAll = async () => {
  loading.value = true
  try {
    const [statisticsData, summaryData, orderStatisticsData] = await Promise.all([
      getDashboardStatistics(),
      getDashboardSummary(),
      getOrderStatistics()
    ])
    stats.value = statisticsData
    summary.value = summaryData
    orderStats.value = orderStatisticsData
  } catch (e) {
    console.error('Failed to load dashboard data', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAll()
  initSettleRange()
  loadSettlement()
})
</script>

<style scoped>
.stat-card {
  margin-bottom: 16px;
}
.settle-item {
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
</style>
