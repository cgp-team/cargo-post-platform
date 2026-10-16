<template>
  <!-- 权限（两个位任一）+ 能力（probeAiAssistantReady）两个条件都成立才渲染；
       任一不成立 ⇒ 这一段整个从 DOM 上消失，而不是留一个点了没反应的按钮（契约 §0 红线）。 -->
  <div v-hasPermi="['transport:ai:task:run', 'transport:ai:scenario:query']">
    <template v-if="showFab">
      <el-button
        class="ai-assistant-fab"
        type="primary"
        circle
        :icon="MagicStick"
        title="AI 辅助（只出参考意见）"
        @click="open = true"
      />
      <el-drawer v-model="open" title="AI 辅助（只出参考意见）" :size="drawerSize">
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="每个结果都是草稿：后端不会替任何人改单、派车、发消息。要落地，请照建议去既有页面自己操作。"
        />
        <AiTaskPanel />
      </el-drawer>
    </template>
  </div>
</template>

<script setup lang="ts">
import { MagicStick } from '@element-plus/icons-vue'
import AiTaskPanel from './AiTaskPanel.vue'
import { probeAiAssistantReady } from '@/hooks/web/useAiDraft'

defineOptions({ name: 'AiAssistantFab' })

const showFab = ref(false)
const open = ref(false)
const drawerSize = ref('720px')

onMounted(async () => {
  drawerSize.value = window.innerWidth < 768 ? '100%' : '720px'
  // 能力没就绪 ⇒ 不挂载（fail closed；状态查询失败也一样）
  showFab.value = await probeAiAssistantReady()
})
</script>

<style scoped>
.ai-assistant-fab {
  position: fixed;
  right: 40px;
  /* 让开右下角的返回顶部（el-backtop 默认 right:40 / bottom:40），免得两个圆按钮叠在一起 */
  bottom: 104px;
  z-index: 200;
  box-shadow: var(--el-box-shadow-light);
}
</style>
