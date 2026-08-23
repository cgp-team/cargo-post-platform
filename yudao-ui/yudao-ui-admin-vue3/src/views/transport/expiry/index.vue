<template>
  <ContentWrap title="到期预警">
    <el-form :inline="true">
      <el-form-item label="预警窗口">
        <el-input-number v-model="days" :min="1" :max="365" style="width:140px" />
        <span style="margin-left:8px">天内到期（含已过期）</span>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="getList"><Icon icon="ep:search" />查询</el-button>
      </el-form-item>
    </el-form>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="司机驾驶证" name="driver">
        <el-table v-loading="driverLoading" :data="driverList" stripe border>
          <el-table-column label="司机姓名" prop="name" align="center" />
          <el-table-column label="手机号" prop="mobile" align="center" />
          <el-table-column label="驾驶证号" prop="licenseNo" align="center" />
          <el-table-column label="驾照到期日" prop="licenseExpireDate" align="center" />
          <el-table-column label="剩余天数" align="center">
            <template #default="scope">
              <span :class="{ 'expiry-danger': isDanger(scope.row.licenseExpireDate) }">
                {{ remainDaysText(scope.row.licenseExpireDate) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="车辆保险" name="vehicle">
        <el-table v-loading="vehicleLoading" :data="vehicleList" stripe border>
          <el-table-column label="车牌号" prop="plateNo" align="center" />
          <el-table-column label="保险到期日" prop="insuranceExpireDate" align="center" />
          <el-table-column label="剩余天数" align="center">
            <template #default="scope">
              <span :class="{ 'expiry-danger': isDanger(scope.row.insuranceExpireDate) }">
                {{ remainDaysText(scope.row.insuranceExpireDate) }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </ContentWrap>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import * as DriverApi from '@/api/transport/driver'
import * as VehicleApi from '@/api/transport/vehicle'

defineOptions({ name: 'TransportExpiry' })

const activeTab = ref('driver')
const days = ref(30)
const driverLoading = ref(false)
const vehicleLoading = ref(false)
const driverList = ref<DriverApi.DriverVO[]>([])
const vehicleList = ref<VehicleApi.VehicleVO[]>([])

/** 剩余天数：负数表示已过期 */
const remainDays = (date?: string) => (date ? dayjs(date).startOf('day').diff(dayjs().startOf('day'), 'day') : undefined)
const remainDaysText = (date?: string) => {
  const d = remainDays(date)
  if (d === undefined) return '-'
  if (d < 0) return `已过期 ${-d} 天`
  if (d === 0) return '今天到期'
  return `${d} 天`
}
/** 已过期或 7 天内到期标红 */
const isDanger = (date?: string) => {
  const d = remainDays(date)
  return d !== undefined && d <= 7
}

const getList = async () => {
  driverLoading.value = true
  vehicleLoading.value = true
  try {
    driverList.value = await DriverApi.getDriverExpiringList(days.value)
  } finally {
    driverLoading.value = false
  }
  try {
    vehicleList.value = await VehicleApi.getVehicleExpiringList(days.value)
  } finally {
    vehicleLoading.value = false
  }
}
onMounted(getList)
</script>

<style scoped>
.expiry-danger {
  color: var(--el-color-danger);
  font-weight: bold;
}
</style>
