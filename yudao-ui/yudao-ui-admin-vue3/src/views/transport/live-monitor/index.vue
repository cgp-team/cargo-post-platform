<template>
  <WorkbenchShell
    title="在途监控"
    :tabs="tabs"
    hint="实时那一格看的是当前位置与班次执行，回放那一格看的是某一段跑过的轨迹；两格共用同一批车辆坐标接口。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../workbench/types'
import MonitoringMap from '../monitoring/index.vue'
import MonitoringReplay from '../monitoring/replay.vue'

/**
 * 在途监控工作台（队列 #104）。
 *
 * 智慧大屏没有并进来：它是全屏路由（`entry.vue` 只做 `replace`），跟运营概览一样属于"看"的面，
 * 不是操作面，塞进 tab 反而会把那块 16:9 的版式压坏。
 */
defineOptions({ name: 'TransportMonitorWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'map', label: '实时监控', perms: ['transport:monitoring:query'], component: MonitoringMap },
  { name: 'replay', label: '轨迹回放', perms: ['transport:monitoring:query'], component: MonitoringReplay },
]
</script>
