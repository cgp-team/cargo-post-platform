<template>
  <ContentWrap title="开发者中心">
    <!-- 开发者模式 -->
    <ContentWrap title="开发者模式">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="开发者模式">
          <el-tag :type="status?.developerMode ? 'success' : 'info'" size="large">
            {{ status?.developerMode ? '已开启' : '未开启' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="说明">
          开启开发者模式后，可以访问实验性运输调度与模拟功能。
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 16px">
        <el-button
          v-if="!status?.developerMode"
          v-hasPermi="['transport:developer:access']"
          type="primary"
          :loading="toggling"
          @click="handleEnable"
        >
          <Icon icon="ep:setting" />开启开发者模式
        </el-button>
        <el-button
          v-else
          v-hasPermi="['transport:developer:access']"
          type="danger"
          :loading="toggling"
          @click="handleDisable"
        >
          <Icon icon="ep:close" />关闭开发者模式
        </el-button>
      </div>
    </ContentWrap>

    <!-- 模拟环境 -->
    <ContentWrap title="模拟环境">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="环境状态">
          <el-tag
            :type="status?.environmentSimulationEnabled ? 'success' : 'warning'"
            size="large"
          >
            {{ status?.environmentSimulationEnabled ? '已启用' : '服务器未启用' }}
          </el-tag>
          <span v-if="!status?.environmentSimulationEnabled" style="margin-left: 8px; color: #909399; font-size: 12px">
            模拟环境由服务器配置 SIMULATION_ENABLED 控制
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="模拟运营查看">
          <el-tag :type="viewTagType" size="default">
            {{ viewLabel }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="模拟控制">
          <el-tag :type="controlTagType" size="default">
            {{ controlLabel }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 16px">
        <el-button
          v-hasPermi="['transport:simulation:view']"
          type="primary"
          :disabled="!canEnterSimulation"
          @click="goSimulation"
        >
          <Icon icon="ep:video-play" />进入模拟运营
        </el-button>
        <span v-if="!canEnterSimulation" style="margin-left: 12px; color: #909399; font-size: 12px">
          {{ enterDisabledReason }}
        </span>
      </div>
    </ContentWrap>

    <!-- 注意事项 -->
    <ContentWrap title="注意事项">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          模拟运营属于开发测试功能，不影响正常真实运营数据。真实 GPS 位置始终优先于模拟位置。
        </template>
      </el-alert>
    </ContentWrap>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as DeveloperApi from '@/api/transport/developer'
import type { DeveloperStatusVO } from '@/api/transport/developer'
import { useRouter } from 'vue-router'

defineOptions({ name: 'TransportDeveloper' })

const message = useMessage()
const { push } = useRouter()

const status = ref<DeveloperStatusVO | null>(null)
const toggling = ref(false)

/** 获取状态 */
const getStatus = async () => {
  try {
    status.value = await DeveloperApi.getDeveloperStatus()
  } catch (e) {
    // 后端返回错误时（如 403），status 保持 null
    console.error('获取开发者状态失败', e)
  }
}

/** 开启开发者模式 */
const handleEnable = async () => {
  try {
    await message.confirm('开启开发者模式后，可以访问开发测试功能。是否继续？', '开启开发者模式')
    toggling.value = true
    await DeveloperApi.enableDeveloperMode()
    message.success('已开启开发者模式')
    await getStatus()
  } catch (e) {
    // 用户取消或后端报错
    if (e !== 'cancel') {
      console.error('开启失败', e)
    }
  } finally {
    toggling.value = false
  }
}

/** 关闭开发者模式 */
const handleDisable = async () => {
  try {
    await message.confirm('关闭开发者模式后，将无法访问模拟运营功能。是否继续？', '关闭开发者模式')
    toggling.value = true
    await DeveloperApi.disableDeveloperMode()
    message.success('已关闭开发者模式')
    await getStatus()
  } catch (e) {
    if (e !== 'cancel') {
      console.error('关闭失败', e)
    }
  } finally {
    toggling.value = false
  }
}

/** 进入模拟运营 */
const goSimulation = () => {
  push('/transport/simulation')
}

// ========== 计算状态标签 ==========

const viewTagType = computed(() => {
  if (!status.value) return 'info'
  if (!status.value.developerMode) return 'info'
  if (!status.value.canViewSimulation) return 'danger'
  return 'success'
})

const viewLabel = computed(() => {
  if (!status.value) return '加载中...'
  if (!status.value.developerMode) return '需开启开发者模式'
  if (!status.value.canViewSimulation) return '无权限'
  return '可用'
})

const controlTagType = computed(() => {
  if (!status.value) return 'info'
  if (!status.value.developerMode) return 'info'
  if (!status.value.environmentSimulationEnabled) return 'warning'
  if (!status.value.canControlSimulation) return 'danger'
  return 'success'
})

const controlLabel = computed(() => {
  if (!status.value) return '加载中...'
  if (!status.value.developerMode) return '需开启开发者模式'
  if (!status.value.environmentSimulationEnabled) return '服务器未启用'
  if (!status.value.canControlSimulation) return '无权限'
  return '可用'
})

const canEnterSimulation = computed(() => {
  if (!status.value) return false
  return status.value.developerMode && status.value.canViewSimulation
})

const enterDisabledReason = computed(() => {
  if (!status.value) return ''
  if (!status.value.developerMode) return '请先开启开发者模式'
  if (!status.value.canViewSimulation) return '当前账号没有模拟查看权限'
  return ''
})

onMounted(getStatus)
</script>
