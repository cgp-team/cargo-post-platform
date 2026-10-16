<template>
  <ContentWrap title="AI 辅助（只出参考意见）">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="这一页的每个结果都是草稿：后端不会替任何人改单、派车、发消息。要落地，请照建议去既有页面自己操作。"
    />
    <AiTaskPanel />
  </ContentWrap>
</template>

<script setup lang="ts">
import AiTaskPanel from './AiTaskPanel.vue'

defineOptions({ name: 'TransportAiAssist' })

/**
 * 路由页（菜单 7150）**只是给 AiTaskPanel 一个壳**：
 * 六格的实现只有一份，右下角悬浮助手挂的也是它 —— 两边各写一遍必然漂移，
 * 而漂移点正好落在"只出草稿 / 未就绪就明说"这几条最容易破的纪律上。
 */
</script>
