<template>
  <WorkbenchShell
    title="AI 辅助"
    :tabs="tabs"
    hint="每一格都是草稿：后端不替任何人改单、派车、发消息。能力没就绪的格子会明说未就绪，不会拿模板话冒充模型结论。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../../workbench/types'
import AiAssistPage from '../assist/index.vue'
import AiCapabilityPage from '../capability/index.vue'
import AiCapacityConflictPage from '../capacity-conflict/index.vue'
import AiQueryBuilderPage from '../query-builder/index.vue'
import AiInvocationLogPage from '../invocation-log/index.vue'

/**
 * AI 工作台（队列 #104）。
 *
 * 为什么这一格要留在 `views/transport/ai/**` 而不是和其它工作台一起放 `views/transport/workbench/`：
 * 第 14、15 本静态闸按**目录**认定"AI 面"（`check_admin_ai_data_mask` 的 `AI_ROOTS`、
 * `check_ai_assist_boundaries` 的边界规则）。搬出去就等于把 AI 的入口从闸的视野里挪走 ——
 * 那正是闸最不该有的失效方式。`WorkbenchShell.vue` 本身不含任何 AI 调用，留在外面没问题。
 *
 * 与右下角悬浮助手的关系是**分层**不是重复：悬浮助手是跨页随手一问（只有六格草稿面板），
 * 这一页是能坐下来逐项核对的整页，且比面板多出能力状态、空间冲突、查询构造器、调用日志四格。
 */
defineOptions({ name: 'TransportAiWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'assist', label: '辅助任务', perms: ['transport:ai:scenario:query'], component: AiAssistPage },
  { name: 'capability', label: '能力状态', perms: ['transport:ai:capability:query'], component: AiCapabilityPage },
  { name: 'conflict', label: '客货空间检视', perms: ['transport:ai:capacity-conflict:query'], component: AiCapacityConflictPage },
  { name: 'query', label: '查询构造器', perms: ['transport:ai:saved-query:query'], component: AiQueryBuilderPage },
  { name: 'log', label: '调用日志', perms: ['transport:ai:invocation-log:query'], component: AiInvocationLogPage },
]
</script>
