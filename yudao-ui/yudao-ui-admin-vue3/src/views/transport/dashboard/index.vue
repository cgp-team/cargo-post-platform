<template>
  <ContentWrap title="客货邮模块接入验证">
    <el-skeleton v-if="loading" :rows="2" animated />
    <el-alert v-else-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <el-result v-else icon="success" title="模块已接入" :sub-title="status" />
    <el-button class="mt-16px" :loading="loading" @click="loadStatus">重新验证</el-button>
  </ContentWrap>
</template>

<script setup lang="ts">
import { getTransportModuleStatus } from '@/api/transport'

defineOptions({ name: 'TransportDashboard' })

const loading = ref(false)
const status = ref('')
const errorMessage = ref('')

const loadStatus = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    status.value = await getTransportModuleStatus()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : 'transport 模块连接失败'
  } finally {
    loading.value = false
  }
}

onMounted(loadStatus)
</script>
