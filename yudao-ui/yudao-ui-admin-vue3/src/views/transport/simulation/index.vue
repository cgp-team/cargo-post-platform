<template>
  <ContentWrap title="模拟运营控制">
    <ContentWrap>
      <el-alert type="info" :closable="false" show-icon
        title="模拟运营引擎：先启用 SIMULATION_ENABLED=true（生产默认 false）。选择一张已派单方案 + 车辆，启动后车辆沿真实道路 polyline 推进；首页实时公交显示「模拟运营」，司机真实上报(REAL)优先于模拟。" />
    </ContentWrap>

    <!-- ① 方案选择 -->
    <ContentWrap title="① 选择方案">
      <el-form :inline="true" :model="queryParams" @submit.prevent="getPlanList">
        <el-form-item label="方案状态">
          <el-select v-model="queryParams.status" placeholder="请选择" clearable style="width:120px">
            <el-option label="待审核" :value="0" />
            <el-option label="已下发" :value="1" />
            <el-option label="执行中" :value="2" />
            <el-option label="已完成" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getPlanList"><Icon icon="ep:search" />查询</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="planLoading" :data="planList" stripe border highlight-current-row
        @current-change="handlePlanSelect" style="margin-top:12px">
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column label="方案ID" prop="id" align="center" width="90" />
        <el-table-column label="状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="planTag(scope.row.status)">{{ planStatusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="总里程(km)" prop="totalDistance" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
      </el-table>
      <Pagination :total="planTotal" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize"
        @pagination="getPlanList" />
    </ContentWrap>

    <!-- ② 车辆 + 控制 -->
    <ContentWrap title="② 选择车辆并控制">
      <el-form inline>
        <el-form-item label="车辆">
          <el-select v-model="vehicleId" placeholder="请先选择方案" style="width:220px">
            <el-option v-for="v in vehicles" :key="v.vehicleId"
              :label="`${v.plateNo || '车辆' + v.vehicleId}（ID ${v.vehicleId}）`" :value="v.vehicleId!" />
          </el-select>
        </el-form-item>
        <el-form-item label="倍速">
          <el-select v-model="multiplier" style="width:110px">
            <el-option v-for="m in [1, 5, 10, 30, 60]" :key="m" :label="`${m}x`" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="!vehicleId || !planId" @click="handleStart"><Icon icon="ep:video-play" />启动</el-button>
          <el-button :disabled="!vehicleId" @click="handlePause"><Icon icon="ep:video-pause" />暂停</el-button>
          <el-button :disabled="!vehicleId" @click="handleResume"><Icon icon="ep:video-play" />恢复</el-button>
          <el-button :disabled="!vehicleId" @click="handleSpeed"><Icon icon="ep:refresh-right" />倍速生效</el-button>
          <el-button type="danger" :disabled="!vehicleId" @click="handleReset"><Icon icon="ep:refresh-left" />重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 运行状态（3s 轮询） -->
      <el-descriptions v-if="status" title="运行状态（每 3 秒刷新）" :column="3" border style="margin-top:12px">
        <el-descriptions-item label="状态">
          <el-tag :type="statusTag(status.status)">{{ status.statusName }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="方案ID">{{ status.planId }}</el-descriptions-item>
        <el-descriptions-item label="倍速">{{ status.multiplier }}x</el-descriptions-item>
        <el-descriptions-item label="已模拟">{{ fmtSeconds(status.simSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="总时长">{{ fmtSeconds(status.totalSimSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="当前站">{{ status.currentStationName || '—' }}{{ status.arrived ? '（停靠中）' : '' }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else-if="vehicleId" description="该车辆尚未启动模拟（或未启用 SIMULATION_ENABLED）" style="margin-top:12px" />
    </ContentWrap>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as SimulationApi from '@/api/transport/simulation'
import * as DispatchApi from '@/api/transport/dispatch'
import * as VehicleApi from '@/api/transport/vehicle'
defineOptions({ name: 'TransportSimulation' })
const message = useMessage()

// ---------- ① 方案列表 ----------
const planLoading = ref(false)
const planTotal = ref(0)
const planList = ref<DispatchApi.DispatchPlanVO[]>([])
const queryParams = reactive({ pageNo: 1, pageSize: 10, status: undefined as number | undefined })
const planId = ref<number>()

const getPlanList = async () => {
  planLoading.value = true
  try {
    const res = await DispatchApi.getDispatchPlanPage(queryParams)
    planList.value = res.list
    planTotal.value = res.total
  } finally {
    planLoading.value = false
  }
}

// ---------- ② 车辆 + 控制 ----------
const vehicles = ref<{ vehicleId: number; plateNo?: string }[]>([])
const vehicleId = ref<number>()
const multiplier = ref(10)
const status = ref<SimulationApi.SimulationStatusVO | null>(null)

const handlePlanSelect = async (row: DispatchApi.DispatchPlanVO | undefined) => {
  if (!row || !row.id) return
  planId.value = row.id
  vehicleId.value = undefined
  status.value = null
  vehicles.value = []
  try {
    const detail = await DispatchApi.getDispatchPlan(row.id)
    const ids = [...new Set((detail.items || []).map((i) => i.vehicleId).filter(Boolean))] as number[]
    const all = await VehicleApi.getSimpleVehicleList()
    vehicles.value = ids.map((id) => ({
      vehicleId: id,
      plateNo: all.find((v) => v.id === id)?.plateNo
    }))
  } catch (e) {
    vehicles.value = []
  }
}

const handleStart = async () => {
  if (!planId.value || !vehicleId.value) return
  await SimulationApi.startSimulation(planId.value, vehicleId.value, multiplier.value)
  message.success('已启动模拟')
  refreshStatus()
}
const handlePause = async () => {
  if (!vehicleId.value) return
  await SimulationApi.pauseSimulation(vehicleId.value)
  refreshStatus()
}
const handleResume = async () => {
  if (!vehicleId.value) return
  await SimulationApi.resumeSimulation(vehicleId.value)
  refreshStatus()
}
const handleSpeed = async () => {
  if (!vehicleId.value) return
  await SimulationApi.setSimulationSpeed(vehicleId.value, multiplier.value)
  message.success('已设置倍速')
  refreshStatus()
}
const handleReset = async () => {
  if (!vehicleId.value) return
  await SimulationApi.resetSimulation(vehicleId.value)
  message.success('已重置')
  status.value = null
}

const refreshStatus = async () => {
  if (!vehicleId.value) return
  status.value = await SimulationApi.getSimulationStatus(vehicleId.value)
}

const fmtSeconds = (s?: number) => {
  if (s == null) return '—'
  return `${Math.floor(s / 60)}分${s % 60}秒`
}
const planStatusLabel = (s?: number) => ({ 0: '待审核', 1: '已下发', 2: '执行中', 3: '已完成', 4: '已作废' })[s ?? -1] || '—'
type TagType = 'info' | 'primary' | 'success' | 'warning' | 'danger'
const planTag = (s?: number): TagType => (({ 0: 'warning', 1: 'primary', 2: 'success', 3: 'info', 4: 'danger' } as Record<number, TagType>)[s ?? -1]) || 'info'
const statusTag = (s?: number): TagType => (({ 0: 'info', 1: 'success', 2: 'warning', 3: 'info' } as Record<number, TagType>)[s ?? -1]) || 'info'

onMounted(() => {
  getPlanList()
  setInterval(refreshStatus, 3000)
})
</script>
