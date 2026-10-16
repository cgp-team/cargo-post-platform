<template>
  <ContentWrap title="AI 能力状态">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="这一页只报状态，不产生任何 AI 内容"
      description="它回答的唯一问题是「现在能不能用模型」。没有配置推理端点时，它会如实说未就绪——绝不会用模板文案冒充 AI 输出。"
    />
  </ContentWrap>

  <ContentWrap>
    <el-button :loading="loading" @click="handleRefresh"><Icon icon="ep:refresh" />刷新状态</el-button>
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
      description="这一条只说明「状态没读到」，不是「能力不可用」，请重试。"
    />
  </ContentWrap>

  <ContentWrap v-if="status" title="当前状态">
    <el-alert
      :type="status.ready ? 'success' : 'warning'"
      :closable="false"
      show-icon
      :title="status.ready ? '能力已就绪' : '能力未就绪'"
      :description="status.reason"
    />
    <el-descriptions :column="1" border style="margin-top: 16px">
      <el-descriptions-item label="总开关 buspost.ai.enabled">
        <el-tag :type="status.enabled ? 'success' : 'info'">{{ status.enabled ? '已打开' : '未打开' }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="推理端点 buspost.ai.base-url">
        <el-tag :type="status.endpointConfigured ? 'success' : 'info'">
          {{ status.endpointConfigured ? '已配置' : '未配置' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="模型">
        <span>{{ status.model || '（未配置）' }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="是否就绪">
        <el-tag :type="status.ready ? 'success' : 'warning'">{{ status.ready ? '就绪' : '未就绪' }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>
    <el-alert
      style="margin-top: 16px"
      type="info"
      :closable="false"
      title="启用方式（由服务端运维设置，不在本页）"
      description="打开开关并配置端点后，本页即显示「已就绪」。配置属服务端职责（buspost.ai.*），本页不提供任何修改入口。"
    />
  </ContentWrap>
</template>

<script setup lang="ts">
import * as CapabilityApi from '@/api/transport/ai/capability'

defineOptions({ name: 'TransportAiCapability' })

const loading = ref(false)
const errorMsg = ref('')
const status = ref<CapabilityApi.AiCapabilityStatus | null>(null)

const handleRefresh = async () => {
  loading.value = true
  errorMsg.value = ''
  try {
    status.value = await CapabilityApi.getAiCapabilityStatus()
  } catch (e: any) {
    // 失败先清状态再留字：别让上一次的「就绪」留在屏幕上冒充这一次
    status.value = null
    errorMsg.value = '状态没读到：' + (e?.msg || e?.message || '未知原因（这不是「能力不可用」）')
  } finally {
    loading.value = false
  }
}

onMounted(handleRefresh)
</script>
