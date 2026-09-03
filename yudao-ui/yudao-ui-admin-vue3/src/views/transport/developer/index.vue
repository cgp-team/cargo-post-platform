<template>
  <div class="developer-container">
    <div class="developer-header">
      <div class="header-left">
        <span class="header-title">开发者中心</span>
        <el-tag v-if="status?.developerMode" type="success" size="small" effect="dark">Developer Mode</el-tag>
      </div>
      <div class="header-right">
        <el-button v-if="!status?.developerMode" v-hasPermi="['transport:developer:access']"
          type="primary" :loading="toggling" @click="handleEnable">
          <Icon icon="ep:setting" />开启开发者模式
        </el-button>
        <el-button v-else v-hasPermi="['transport:developer:access']"
          type="danger" :loading="toggling" @click="handleDisable">
          <Icon icon="ep:close" />关闭开发者模式
        </el-button>
      </div>
    </div>

    <!-- 环境状态 -->
    <div class="status-bar">
      <div class="status-item" :class="status?.developerMode ? 'active' : 'inactive'">
        <div class="status-dot" />
        <span>Developer Mode</span>
      </div>
      <div class="status-item" :class="status?.environmentSimulationEnabled ? 'active' : 'inactive'">
        <div class="status-dot" />
        <span>Simulation Environment</span>
      </div>
      <div v-for="svc in health?.services" :key="svc.name"
        class="status-item" :class="healthStatusClass(svc.status)">
        <div class="status-dot" />
        <span>{{ svc.name }}</span>
        <span v-if="svc.latencyMs != null" class="latency">{{ svc.latencyMs }}ms</span>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-cards">
      <div class="stat-card">
        <div class="stat-icon primary">
          <Icon icon="ep:setting" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ status?.developerMode ? '已开启' : '未开启' }}</div>
          <div class="stat-label">开发者模式</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon" :class="status?.environmentSimulationEnabled ? 'success' : 'warning'">
          <Icon icon="ep:video-play" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ status?.environmentSimulationEnabled ? '已启用' : '未启用' }}</div>
          <div class="stat-label">模拟环境</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon info">
          <Icon icon="ep:location" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats?.stationCount ?? '—' }}</div>
          <div class="stat-label">站点</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon info">
          <Icon icon="ep:van" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats?.vehicleCount ?? '—' }}</div>
          <div class="stat-label">车辆</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon info">
          <Icon icon="ep:user" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats?.driverCount ?? '—' }}</div>
          <div class="stat-label">司机</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon info">
          <Icon icon="ep:document" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats?.orderCount ?? '—' }}</div>
          <div class="stat-label">订单</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon info">
          <Icon icon="ep:monitor" />
        </div>
        <div class="stat-content">
          <div class="stat-value">{{ stats?.totalSimulations ?? '—' }}</div>
          <div class="stat-label">历史任务</div>
        </div>
      </div>
    </div>

    <!-- 快速操作 -->
    <div class="actions-section">
      <div class="section-title">快速操作</div>
      <div class="action-cards">
        <div class="action-card" @click="goSimulation" :class="{ disabled: !canEnterSimulation }">
          <div class="action-icon primary">
            <Icon icon="ep:video-play" />
          </div>
          <div class="action-content">
            <div class="action-title">进入模拟运营</div>
            <div class="action-desc">启动模拟运行、场景注入、异常测试</div>
          </div>
          <Icon icon="ep:arrow-right" class="action-arrow" />
        </div>
        <div class="action-card" @click="goMonitoring">
          <div class="action-icon success">
            <Icon icon="ep:location" />
          </div>
          <div class="action-content">
            <div class="action-title">车辆监控</div>
            <div class="action-desc">查看车辆实时位置、轨迹回放</div>
          </div>
          <Icon icon="ep:arrow-right" class="action-arrow" />
        </div>
        <div class="action-card" @click="refreshAll">
          <div class="action-icon info">
            <Icon icon="ep:refresh" />
          </div>
          <div class="action-content">
            <div class="action-title">刷新状态</div>
            <div class="action-desc">重新检查系统健康状态和统计数据</div>
          </div>
          <Icon icon="ep:arrow-right" class="action-arrow" />
        </div>
      </div>
    </div>

    <!-- 系统诊断 -->
    <div class="diagnosis-section">
      <div class="section-title">系统诊断</div>
      <div class="diagnosis-table">
        <div class="diagnosis-header">
          <span class="col-service">服务</span>
          <span class="col-status">状态</span>
          <span class="col-latency">延迟</span>
          <span class="col-time">最后检查</span>
        </div>
        <div v-for="svc in health?.services" :key="svc.name" class="diagnosis-row">
          <span class="col-service">{{ svc.name }}</span>
          <span class="col-status">
            <el-tag :type="healthTagType(svc.status)" size="small">
              {{ svc.status === 'HEALTHY' ? '✅ 正常' : svc.status === 'DEGRADED' ? '⚠️ 降级' : '❌ 离线' }}
            </el-tag>
          </span>
          <span class="col-latency">{{ svc.latencyMs != null ? svc.latencyMs + 'ms' : '—' }}</span>
          <span class="col-time">{{ svc.checkedAt || '—' }}</span>
        </div>
        <div v-if="!health?.services?.length" class="diagnosis-empty">
          暂无诊断数据
        </div>
      </div>
    </div>

    <!-- 注意事项 -->
    <div class="notice-section">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          模拟运营属于开发测试功能，不影响正常真实运营数据。真实 GPS 位置始终优先于模拟位置。
          生产环境默认 SIMULATION_ENABLED=false。
        </template>
      </el-alert>
    </div>
  </div>
</template>

<script setup lang="ts">
import * as DeveloperApi from '@/api/transport/developer'
import type { DeveloperStatusVO, DeveloperHealthVO, DeveloperStatisticsVO } from '@/api/transport/developer'
import { useRouter } from 'vue-router'

defineOptions({ name: 'TransportDeveloper' })

const message = useMessage()
const { push } = useRouter()

const status = ref<DeveloperStatusVO | null>(null)
const health = ref<DeveloperHealthVO | null>(null)
const stats = ref<DeveloperStatisticsVO | null>(null)
const toggling = ref(false)

const loadDeveloperStatus = async () => {
  try {
    status.value = await DeveloperApi.getDeveloperStatus()
  } catch (e) {
    console.error('获取开发者状态失败', e)
  }
}

const loadHealth = async () => {
  try {
    health.value = await DeveloperApi.getDeveloperHealth()
  } catch (e) {
    console.error('健康检查失败', e)
  }
}

const loadStatistics = async () => {
  try {
    stats.value = await DeveloperApi.getDeveloperStatistics()
  } catch (e) {
    console.error('获取统计数据失败', e)
  }
}

const refreshAll = async () => {
  await Promise.all([loadDeveloperStatus(), loadHealth(), loadStatistics()])
  message.success('已刷新')
}

const handleEnable = async () => {
  try {
    await message.confirm('开启开发者模式后，可以访问开发测试功能。是否继续？', '开启开发者模式')
    toggling.value = true
    await DeveloperApi.enableDeveloperMode()
    message.success('已开启开发者模式')
    await loadDeveloperStatus()
  } catch (e) {
    if (e !== 'cancel') console.error('开启失败', e)
  } finally {
    toggling.value = false
  }
}

const handleDisable = async () => {
  try {
    await message.confirm('关闭开发者模式后，将无法访问模拟运营功能。是否继续？', '关闭开发者模式')
    toggling.value = true
    await DeveloperApi.disableDeveloperMode()
    message.success('已关闭开发者模式')
    await loadDeveloperStatus()
  } catch (e) {
    if (e !== 'cancel') console.error('关闭失败', e)
  } finally {
    toggling.value = false
  }
}

const canEnterSimulation = computed(() => {
  return status.value?.developerMode && status.value?.canViewSimulation
})

const goSimulation = () => {
  if (!canEnterSimulation.value) {
    message.warning('请先开启开发者模式并确保有模拟查看权限')
    return
  }
  push('/transport/monitoring/simulation')
}

const goMonitoring = () => push('/transport/monitoring')

const healthStatusClass = (svcStatus: string) => {
  if (svcStatus === 'HEALTHY') return 'active'
  if (svcStatus === 'DEGRADED') return 'warning'
  return 'inactive'
}

const healthTagType = (svcStatus: string): 'success' | 'warning' | 'danger' => {
  if (svcStatus === 'HEALTHY') return 'success'
  if (svcStatus === 'DEGRADED') return 'warning'
  return 'danger'
}

onMounted(refreshAll)
</script>

<style scoped>
.developer-container {
  padding: 16px;
  max-width: 1200px;
  margin: 0 auto;
}

.developer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-title {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
}

/* 状态栏 */
.status-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 20px;
  padding: 14px 18px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}

.status-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-item.active .status-dot { background: #67C23A; }
.status-item.warning .status-dot { background: #E6A23C; }
.status-item.inactive .status-dot { background: #909399; }

.latency {
  font-size: 11px;
  color: #909399;
  font-family: monospace;
}

/* 统计卡片 */
.stats-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 24px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}

.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}

.stat-icon.primary { background: #ecf5ff; color: #409EFF; }
.stat-icon.success { background: #f0f9eb; color: #67C23A; }
.stat-icon.warning { background: #fdf6ec; color: #E6A23C; }
.stat-icon.info { background: #f4f4f5; color: #909399; }

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

/* 快速操作 */
.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.action-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 24px;
}

.action-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  cursor: pointer;
  transition: all 0.2s;
}

.action-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  transform: translateY(-1px);
}

.action-card.disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-icon {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
}

.action-icon.primary { background: #ecf5ff; color: #409EFF; }
.action-icon.success { background: #f0f9eb; color: #67C23A; }
.action-icon.info { background: #f4f4f5; color: #909399; }

.action-content {
  flex: 1;
}

.action-title {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.action-desc {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.action-arrow {
  color: #C0C4CC;
}

/* 系统诊断 */
.diagnosis-section {
  margin-bottom: 24px;
}

.diagnosis-table {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.diagnosis-header, .diagnosis-row {
  display: flex;
  align-items: center;
  padding: 12px 18px;
}

.diagnosis-header {
  background: #fafafa;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  border-bottom: 1px solid #f0f0f0;
}

.diagnosis-row {
  font-size: 13px;
  color: #303133;
  border-bottom: 1px solid #f5f5f5;
}

.diagnosis-row:last-child { border-bottom: none; }

.col-service { flex: 1; }
.col-status { width: 100px; text-align: center; }
.col-latency { width: 80px; text-align: center; font-family: monospace; }
.col-time { width: 100px; text-align: center; color: #909399; font-family: monospace; }

.diagnosis-empty {
  padding: 24px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

.notice-section {
  margin-top: 16px;
}
</style>
