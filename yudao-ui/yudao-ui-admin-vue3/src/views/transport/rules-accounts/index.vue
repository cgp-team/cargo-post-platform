<template>
  <WorkbenchShell
    title="规则与账务"
    :tabs="tabs"
    hint="计价规则和事件日历是调度读进去的两个输入（运价、赶集/农忙），钱包是只读查询：这一格只会念账户与流水，没有改账的动作。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../workbench/types'
import PricingRulePage from '../pricingRule/index.vue'
import TownEventPage from '../townEvent/index.vue'
import WalletPage from '../wallet/index.vue'

/**
 * 规则与账务工作台（队列 #104）。
 *
 * 钱包那一格是**刻意只读**的（台账：App 侧只读、写口不接，充值页因缺前置条件被拒），
 * 所以这一格没有按钮可合并、也没有权限位可继承 —— 只有 `transport:wallet:query`。
 * 把它和"计价规则/事件日历"这两个真配置页放一行，是因为值班员调舱位时要同看这三格；
 * 语义差别写在 hint 里，不让"配置"和"查询"混成一个词。
 */
defineOptions({ name: 'TransportRulesWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'pricing', label: '计价规则', perms: ['transport:pricing-rule:query'], component: PricingRulePage },
  { name: 'town-event', label: '事件日历', perms: ['transport:town-event:query'], component: TownEventPage },
  { name: 'wallet', label: '钱包查询', perms: ['transport:wallet:query'], component: WalletPage },
]
</script>
