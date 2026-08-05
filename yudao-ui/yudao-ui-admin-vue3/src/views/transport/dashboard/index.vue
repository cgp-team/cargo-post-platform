<template>
  <ContentWrap title="运营概览">
    <el-row :gutter="16">
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">车辆总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.vehicleCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:van" :size="40" color="#409EFF" />
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">司机总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.driverCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:user" :size="40" color="#67C23A" />
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">站点总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.stationCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:location" :size="40" color="#E6A23C" />
          </div>
        </el-card>
      </el-col>
      <el-col :md="6" :sm="12" :xs="24">
        <el-card shadow="hover" class="stat-card">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-gray-500 text-sm">线路总数</div>
              <div class="text-3xl font-bold mt-2">{{ stats.routeCount ?? '-' }}</div>
            </div>
            <Icon icon="ep:guide" :size="40" color="#F56C6C" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="mt-16px">
      <el-col :span="24">
        <el-card shadow="never">
          <template #header>
            <span class="font-700">快捷入口</span>
          </template>
          <el-row :gutter="12">
            <el-col :md="4" :sm="8" :xs="12" v-for="item in quickLinks" :key="item.label" class="mb-12px">
              <el-button class="w-full" @click="$router.push(item.path)">
                <Icon :icon="item.icon" class="mr-4px" />
                {{ item.label }}
              </el-button>
            </el-col>
          </el-row>
        </el-card>
      </el-col>
    </el-row>
  </ContentWrap>
</template>

<script setup lang="ts">
import { getDashboardStatistics } from '@/api/transport'

defineOptions({ name: 'TransportDashboard' })

const loading = ref(true)
const stats = ref<Record<string, number>>({})

const quickLinks = [
  { label: '站点管理', path: '/transport/station', icon: 'ep:location' },
  { label: '车辆管理', path: '/transport/vehicle', icon: 'ep:van' },
  { label: '线路管理', path: '/transport/route', icon: 'ep:guide' },
  { label: '司机管理', path: '/transport/driver', icon: 'ep:user' },
  { label: '班次管理', path: '/transport/shift', icon: 'ep:clock' },
  { label: '订单管理', path: '/transport/order', icon: 'ep:document' },
]

const loadStats = async () => {
  loading.value = true
  try {
    stats.value = await getDashboardStatistics()
  } catch (e) {
    console.error('Failed to load dashboard stats', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadStats)
</script>

<style scoped>
.stat-card {
  margin-bottom: 16px;
}
</style>
