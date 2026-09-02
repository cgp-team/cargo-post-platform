<template>
  <ContentWrap title="模拟运营中心">
    <!-- 顶部状态栏 -->
    <el-alert v-if="!developerMode" type="warning" :closable="false" show-icon
      title="开发者模式未开启">
      <template #default>
        模拟运营需要先开启开发者模式。
        <el-button link type="primary" @click="goDeveloper">返回开发者中心</el-button>
      </template>
    </el-alert>
    <el-alert v-else-if="!environmentSimulationEnabled" type="info" :closable="false" show-icon
      title="服务器未启用模拟环境">
      <template #default>
        模拟环境由服务器配置 SIMULATION_ENABLED 控制，当前为关闭状态。可以查看已有状态，但无法执行模拟控制。
      </template>
    </el-alert>
    <el-alert v-else-if="!canViewSimulation" type="error" :closable="false" show-icon
      title="当前账号没有模拟运营查看权限">
      <template #default>
        请联系管理员分配 transport:simulation:view 权限。
      </template>
    </el-alert>

    <!-- 状态卡片 -->
    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="8">
        <ContentWrap>
          <template #header>
            <span>运行状态</span>
            <el-button link type="primary" style="float:right" @click="goDeveloper">
              <Icon icon="ep:arrow-left" />返回开发者中心
            </el-button>
          </template>
          <div style="text-align: center; padding: 20px 0">
            <el-tag :type="statusTagType" size="large" effect="dark" style="font-size: 16px; padding: 8px 24px">
              {{ statusLabel }}
            </el-tag>
            <div v-if="status" style="margin-top: 12px; color: #909399; font-size: 13px">
              倍速: {{ status.multiplier }}× | 已模拟: {{ fmtSeconds(status.simSeconds) }} / {{ fmtSeconds(status.totalSimSeconds) }}
            </div>
          </div>
        </ContentWrap>
      </el-col>
      <el-col :span="16">
        <ContentWrap>
          <template #header>
            <span>当前模拟方案</span>
          </template>
          <div v-if="planId" style="padding: 8px 0">
            <el-descriptions :column="2" size="small">
              <el-descriptions-item label="方案ID">{{ planId }}</el-descriptions-item>
              <el-descriptions-item label="车辆ID">{{ vehicleId || '—' }}</el-descriptions-item>
              <el-descriptions-item label="当前站">{{ status?.currentStationName || '—' }}</el-descriptions-item>
              <el-descriptions-item label="到达状态">
                <el-tag v-if="status?.arrived" type="success" size="small">停靠中</el-tag>
                <el-tag v-else-if="simStatus === 1" type="primary" size="small">行驶中</el-tag>
                <span v-else>—</span>
              </el-descriptions-item>
            </el-descriptions>
          </div>
          <el-empty v-else description="未选择方案" :image-size="60" />
        </ContentWrap>
      </el-col>
    </el-row>

    <!-- ① 选择方案 -->
    <ContentWrap title="① 选择运营方案">
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
      <div v-if="!planLoading && planList.length === 0" style="text-align: center; padding: 24px 0">
        <el-empty description="当前暂无可模拟调度方案">
          <template #description>
            <div>
              <p style="color: #909399">当前暂无可模拟调度方案</p>
              <p style="color: #909399; font-size: 12px">模拟运营需要先生成调度方案</p>
            </div>
          </template>
        </el-empty>
      </div>
      <Pagination :total="planTotal" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize"
        @pagination="getPlanList" />
    </ContentWrap>

    <!-- ② 选择车辆 -->
    <ContentWrap title="② 选择车辆">
      <el-form inline>
        <el-form-item label="车辆">
          <el-select v-model="vehicleId" placeholder="请先选择方案" style="width:220px" :disabled="!planId">
            <el-option v-for="v in vehicles" :key="v.vehicleId"
              :label="`${v.plateNo || '车辆' + v.vehicleId}（ID ${v.vehicleId}）`" :value="v.vehicleId!" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="planId && vehicles.length === 0 && !planLoading" style="text-align: center; padding: 16px 0">
        <el-empty description="当前方案没有可模拟车辆" :image-size="60" />
      </div>
      <div v-if="!planId" style="text-align: center; padding: 16px 0; color: #909399">
        请先选择运营方案
      </div>
    </ContentWrap>

    <!-- ③ 模拟控制 -->
    <ContentWrap title="③ 模拟控制">
      <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
        <el-button type="primary" :disabled="!canStart" @click="handleStart">
          <Icon icon="ep:video-play" />启动
        </el-button>
        <el-button :disabled="!canPause" @click="handlePause">
          <Icon icon="ep:video-pause" />暂停
        </el-button>
        <el-button :disabled="!canResume" @click="handleResume">
          <Icon icon="ep:video-play" />继续
        </el-button>
        <el-button type="danger" :disabled="!canReset" @click="handleReset">
          <Icon icon="ep:refresh-left" />重置
        </el-button>
        <el-divider direction="vertical" />
        <span style="color: #606266; font-size: 14px">倍速:</span>
        <el-select v-model="multiplier" style="width:90px" :disabled="!canControl">
          <el-option v-for="m in [1, 2, 5, 10, 20]" :key="m" :label="`${m}×`" :value="m" />
        </el-select>
        <el-button :disabled="!canControl" @click="handleSpeed">
          <Icon icon="ep:refresh-right" />设置倍速
        </el-button>
      </div>
      <div v-if="!canControl" style="margin-top: 8px; color: #E6A23C; font-size: 12px">
        {{ controlDisabledReason }}
      </div>
      <div style="margin-top: 8px; color: #909399; font-size: 12px">
        模拟运行不会改变真实运营状态。真实 GPS 位置始终优先于模拟位置。
      </div>
    </ContentWrap>

    <!-- ④ 运行状态（轮询） -->
    <ContentWrap v-if="status" title="④ 运行状态">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType">{{ status.statusName }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="方案ID">{{ status.planId }}</el-descriptions-item>
        <el-descriptions-item label="倍速">{{ status.multiplier }}×</el-descriptions-item>
        <el-descriptions-item label="已模拟">{{ fmtSeconds(status.simSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="总时长">{{ fmtSeconds(status.totalSimSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="当前站">
          {{ status.currentStationName || '—' }}
          <el-tag v-if="status.arrived" type="success" size="small" style="margin-left:4px">停靠中</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </ContentWrap>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as SimulationApi from '@/api/transport/simulation'
import * as DeveloperApi from '@/api/transport/developer'
import * as DispatchApi from '@/api/transport/dispatch'
import * as VehicleApi from '@/api/transport/vehicle'
import { useRouter } from 'vue-router'

defineOptions({ name: 'TransportSimulation' })

const message = useMessage()
const { push } = useRouter()

// ========== 开发者状态 ==========
const developerMode = ref(false)
const environmentSimulationEnabled = ref(false)
const canViewSimulation = ref(false)
const canControlSimulation = ref(false)

const loadDeveloperStatus = async () => {
  try {
    const dev = await DeveloperApi.getDeveloperStatus()
    developerMode.value = dev.developerMode
    environmentSimulationEnabled.value = dev.environmentSimulationEnabled
    canViewSimulation.value = dev.canViewSimulation
    canControlSimulation.value = dev.canControlSimulation
  } catch (e) {
    console.error('获取开发者状态失败', e)
  }
}

// ========== 方案列表 ==========
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
  } catch (e: any) {
    if (e?.code === 403) {
      message.error('当前账号没有查看调度方案的权限，请联系管理员')
    } else {
      message.error('调度方案加载失败，请稍后重试')
    }
  } finally {
    planLoading.value = false
  }
}

// ========== 车辆选择 ==========
const vehicles = ref<{ vehicleId: number; plateNo?: string }[]>([])
const vehicleId = ref<number>()
const multiplier = ref(10)
const status = ref<SimulationApi.SimulationStatusVO | null>(null)
const simStatus = computed(() => status.value?.status ?? -1)

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

// ========== 模拟控制 ==========
const handleStart = async () => {
  if (!planId.value || !vehicleId.value) return
  try {
    await message.confirm('确认启动模拟运行？', '启动模拟')
    await SimulationApi.startSimulation(planId.value, vehicleId.value, multiplier.value)
    message.success('已启动模拟')
    refreshStatus()
  } catch (e) {
    if (e !== 'cancel') {
      console.error('启动失败', e)
    }
  }
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

const handleReset = async () => {
  if (!vehicleId.value) return
  try {
    await message.confirm('确认重置当前模拟运行状态？', '重置模拟')
    await SimulationApi.resetSimulation(vehicleId.value)
    message.success('已重置')
    status.value = null
  } catch (e) {
    if (e !== 'cancel') {
      console.error('重置失败', e)
    }
  }
}

const handleSpeed = async () => {
  if (!vehicleId.value) return
  await SimulationApi.setSimulationSpeed(vehicleId.value, multiplier.value)
  message.success('已设置倍速')
  refreshStatus()
}

// ========== 状态刷新 ==========
let pollTimer: ReturnType<typeof setInterval> | null = null

const refreshStatus = async () => {
  if (!vehicleId.value) return
  try {
    status.value = await SimulationApi.getSimulationStatus(vehicleId.value)
  } catch (e) {
    console.error('获取状态失败', e)
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(refreshStatus, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 状态变化时管理轮询
watch(simStatus, (newStatus) => {
  if (newStatus === 1) {
    // RUNNING → 开始轮询
    startPolling()
  } else {
    // STOPPED / PAUSED / COMPLETED → 停止轮询
    stopPolling()
  }
})

// ========== 计算状态 ==========
const statusTagType = computed((): 'info' | 'success' | 'warning' | 'danger' => {
  switch (simStatus.value) {
    case 0: return 'info'     // STOPPED
    case 1: return 'success'  // RUNNING
    case 2: return 'warning'  // PAUSED
    case 3: return 'info'     // COMPLETED
    default: return 'info'
  }
})

const statusLabel = computed(() => {
  switch (simStatus.value) {
    case 0: return '已停止'
    case 1: return '运行中'
    case 2: return '已暂停'
    case 3: return '已完成'
    default: return '未启动'
  }
})

const canControl = computed(() => {
  return developerMode.value && environmentSimulationEnabled.value && canControlSimulation.value
})

const canStart = computed(() => {
  if (!canControl.value || !vehicleId.value || !planId.value) return false
  return simStatus.value === -1 || simStatus.value === 0 || simStatus.value === 3
})

const canPause = computed(() => {
  if (!canControl.value || !vehicleId.value) return false
  return simStatus.value === 1
})

const canResume = computed(() => {
  if (!canControl.value || !vehicleId.value) return false
  return simStatus.value === 2
})

const canReset = computed(() => {
  if (!canControl.value || !vehicleId.value) return false
  return simStatus.value >= 0
})

const controlDisabledReason = computed(() => {
  if (!developerMode.value) return '请先开启开发者模式'
  if (!environmentSimulationEnabled.value) return '服务器未启用模拟环境'
  if (!canControlSimulation.value) return '当前账号没有模拟控制权限'
  return ''
})

// ========== 工具函数 ==========
const fmtSeconds = (s?: number) => {
  if (s == null) return '—'
  return `${Math.floor(s / 60)}分${s % 60}秒`
}

const planStatusLabel = (s?: number) => ({ 0: '待审核', 1: '已下发', 2: '执行中', 3: '已完成', 4: '已作废' })[s ?? -1] || '—'
type TagType = 'info' | 'primary' | 'success' | 'warning' | 'danger'
const planTag = (s?: number): TagType => (({ 0: 'warning', 1: 'primary', 2: 'success', 3: 'info', 4: 'danger' } as Record<number, TagType>)[s ?? -1]) || 'info'

const goDeveloper = () => {
  push('/transport/developer')
}

// ========== 生命周期 ==========
onMounted(async () => {
  await loadDeveloperStatus()
  if (canViewSimulation.value) {
    getPlanList()
  }
})

onUnmounted(() => {
  stopPolling()
})
</script>
