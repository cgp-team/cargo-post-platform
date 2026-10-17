<template>
  <WorkbenchShell
    title="运力资源"
    :tabs="tabs"
    hint="站点、车辆、司机、线路、班次、人车绑定、证照保险到期：同一批运力台账，原先是七个菜单入口，这里收成一行。每一格仍按各自权限显示。"
  />
</template>

<script setup lang="ts">
import WorkbenchShell from '../workbench/WorkbenchShell.vue'
import type { WorkbenchTab } from '../workbench/types'
import StationList from '../station/index.vue'
import VehicleList from '../vehicle/index.vue'
import DriverList from '../driver/index.vue'
import RouteList from '../route/index.vue'
import ShiftList from '../shift/index.vue'
import DriverVehicleList from '../driver-vehicle/index.vue'
import ExpiryList from '../expiry/index.vue'

/**
 * 运力资源工作台（队列 #104）。
 *
 * 这里**只是导航层的合并**：七格各是原来那个页面组件本身，取数、表单、写口、权限位一行都没改，
 * 所以旧路由 `/transport/station/list` 这些照样能用（菜单只是 `visible=0`，路由照样生成），
 * 从别的页面跳过来的深链不会断。
 */
defineOptions({ name: 'TransportCapacityWorkbench' })

const tabs: WorkbenchTab[] = [
  { name: 'station', label: '站点', perms: ['transport:station:query'], component: StationList },
  { name: 'vehicle', label: '车辆', perms: ['transport:vehicle:query'], component: VehicleList },
  { name: 'driver', label: '司机', perms: ['transport:driver:query'], component: DriverList },
  { name: 'route', label: '线路', perms: ['transport:route:query'], component: RouteList },
  { name: 'shift', label: '班次', perms: ['transport:shift:query'], component: ShiftList },
  { name: 'binding', label: '人车绑定', perms: ['transport:driver:query'], component: DriverVehicleList },
  { name: 'expiry', label: '到期预警', perms: ['transport:driver:query'], component: ExpiryList },
]
</script>
