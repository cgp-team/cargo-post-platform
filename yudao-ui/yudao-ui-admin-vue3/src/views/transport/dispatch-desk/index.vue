<template>
  <WorkbenchShell
    title="调度"
    :tabs="tabs"
    hint="前两格读的是同一批待调度单（同一个 order-pool 接口），差别在动作：那一格做归集与派单审核，这一格看多段链路并重排某一段。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../workbench/types'
import DispatchDesk from '../dispatch/index.vue'
import DispatchCenter from '../dispatch-center/index.vue'
import TopologyView from '../topology/index.vue'

/**
 * 调度工作台（队列 #104）。
 *
 * ⚠ 一个必须说出来的口径：`派单工作台` 与 `订单池 + 运输链` 两格都会显示待调度单，
 *   合并之后这两个数就在**同一个页面**上了。台账 D-66 记过"待调度单数同页两个答案"，
 *   本次合并没有去证伪那两个读数是否同源同值 —— 只是把它们摆到了一起，
 *   所以这里写明它们是同一个接口的两次取数，不要读成两份业务事实。
 *
 * 拓扑那一格保留是因为它吃 `?orderId=`：调度中心的"看运输链"跳过来时靠这个参数直接展开那一条单，
 * 挂在壳里时 `useRoute()` 拿到的就是本壳的路由，参数照样读得到。
 */
defineOptions({ name: 'TransportDispatchWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'desk', label: '派单工作台', perms: ['transport:dispatch:query'], component: DispatchDesk },
  { name: 'chain', label: '订单池 + 运输链', perms: ['transport:dispatch:query'], component: DispatchCenter },
  { name: 'topology', label: '运输拓扑', perms: ['transport:topology:query'], component: TopologyView },
]
</script>
