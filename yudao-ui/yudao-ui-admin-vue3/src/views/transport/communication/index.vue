<template>
  <WorkbenchShell
    title="通知与反馈"
    :tabs="tabs"
    hint="公告发给所有人、用户通知按人投递、意见反馈是收回来的那一份。三格读的是三张表，别读成一条消息的三个状态。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../workbench/types'
import NoticeList from '../notice/index.vue'
import NotificationList from '../notification/index.vue'
import FeedbackList from '../operation/feedback/index.vue'

/**
 * 通知与反馈工作台（队列 #104）。
 *
 * 名字容易混是事实：`公告管理`（notice，面向全体的平台公告）与 `用户通知`（notification，
 * 按人投递的站内消息，含 ADMIN 告警的已读口）是两套东西，页面上各占一格、标题各自如实写。
 * 合并成一行 tab 只是少一个菜单入口，不合并语义。
 */
defineOptions({ name: 'TransportMessageWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'notice', label: '公告管理', perms: ['transport:notice:query'], component: NoticeList },
  { name: 'notification', label: '用户通知', perms: ['transport:notification:query'], component: NotificationList },
  { name: 'feedback', label: '意见反馈', perms: ['transport:feedback:query'], component: FeedbackList },
]
</script>
