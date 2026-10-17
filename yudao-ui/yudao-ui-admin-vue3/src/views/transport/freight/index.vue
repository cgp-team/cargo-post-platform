<template>
  <WorkbenchShell
    title="货运与商品"
    :tabs="tabs"
    hint="运输订单、商品订单、货物交接是同一批货的三个阶段；商品是商城那一侧的目录，读权限与前面三个不同，所以单独一格。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../workbench/types'
import OrderList from '../order/index.vue'
import ProductOrderList from '../productOrder/index.vue'
import HandoverList from '../handover/index.vue'
import ProductList from '../product/index.vue'

/**
 * 货运与商品工作台（队列 #104）。
 *
 * 四格都带写口（新增/编辑/删除/发货/完成/争议），**权限位一律沿用原页面里的 `v-hasPermi`**，
 * 这一层不替它们重述一遍权限：壳只按读权限决定"这一格显示不显示"，
 * 写权限的逐按钮判断还在各页自己的模板里（改到那边去会让第 9 本写按钮闸的在册路径失效）。
 */
defineOptions({ name: 'TransportFreightWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'order', label: '运输订单', perms: ['transport:order:query'], component: OrderList },
  { name: 'product-order', label: '商品订单', perms: ['transport:product-order:query'], component: ProductOrderList },
  { name: 'handover', label: '货物交接', perms: ['transport:handover:query'], component: HandoverList },
  { name: 'product', label: '商品目录', perms: ['transport:product:query'], component: ProductList },
]
</script>
