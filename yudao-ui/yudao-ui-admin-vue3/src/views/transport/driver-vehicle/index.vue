<template>
  <ContentWrap title="人车绑定">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="司机">
          <el-select v-model="queryParams.driverId" placeholder="请选择司机" clearable filterable style="width:180px">
            <el-option v-for="d in driverOptions" :key="d.id" :label="d.name" :value="d.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择" clearable style="width:120px">
            <el-option label="绑定中" :value="1" />
            <el-option label="已解绑" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:driver:update']" @click="openBind">
        <Icon icon="ep:link" />绑定车辆
      </el-button>
      <el-alert
          v-if="listError"
          type="error"
          :closable="false"
          show-icon
          :title="listError"
          description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
      />
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="司机姓名" prop="driverName" align="center" width="120" />
        <el-table-column label="车牌号" prop="plateNo" align="center" width="140" />
        <el-table-column label="绑定时间" prop="bindTime" align="center" width="180" :formatter="dateFormatter" />
        <el-table-column label="解绑时间" prop="unbindTime" align="center" width="180">
          <template #default="scope">{{ scope.row.unbindTime ? formatDate(scope.row.unbindTime) : '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" prop="status" align="center" width="100">
          <template #default="scope">
            <el-tag :type="scope.row.status === 1 ? 'success' : 'info'" size="small">
              {{ scope.row.status === 1 ? '绑定中' : '已解绑' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" align="center" width="100">
          <template #default="scope">
            <el-button v-if="scope.row.status === 1" link type="danger" v-hasPermi="['transport:driver:update']" @click="handleUnbind(scope.row)">
              解绑
            </el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无绑定记录，可在司机/车辆页发起绑定" />
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <!-- 绑定 Dialog -->
    <Dialog v-model="bindVisible" title="绑定车辆" width="480px">
      <el-form ref="bindFormRef" :model="bindForm" :rules="bindRules" label-width="80px">
        <el-form-item label="司机" prop="driverId">
          <el-select v-model="bindForm.driverId" placeholder="请选择司机" clearable filterable style="width:100%">
            <el-option v-for="d in driverOptions" :key="d.id" :label="d.name" :value="d.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="车辆" prop="vehicleId">
          <el-select v-model="bindForm.vehicleId" placeholder="请选择车辆" clearable filterable style="width:100%">
            <el-option v-for="v in vehicleOptions" :key="v.id" :label="v.plateNo" :value="v.id!" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="bindVisible = false">取 消</el-button>
        <el-button type="primary" :loading="bindLoading" @click="submitBind">绑 定</el-button>
      </template>
    </Dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import type { DriverVehicleVO } from '@/api/transport/driver-vehicle'
import type { DriverVO } from '@/api/transport/driver'
import type { VehicleVO } from '@/api/transport/vehicle'
import * as DriverVehicleApi from '@/api/transport/driver-vehicle'
import * as DriverApi from '@/api/transport/driver'
import * as VehicleApi from '@/api/transport/vehicle'
import { dateFormatter, formatDate } from '@/utils/formatTime'
defineOptions({ name: 'TransportDriverVehicle' })
const message = useMessage()
const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<DriverVehicleVO[]>([])

type QueryParams = { pageNo: number; pageSize: number; driverId?: number; status?: number }
const queryParams = reactive<QueryParams>({ pageNo: 1, pageSize: 10, driverId: undefined, status: undefined })

const driverOptions = ref<DriverVO[]>([])
const vehicleOptions = ref<VehicleVO[]>([])
const loadOptions = async () => {
  // 司机与班次两张字典互不依赖：串成链 = 两个 RTT 之和，且司机那一个抛错时车辆根本不取。
  // 并发跑，失败的那一格留空（错误由请求层提示），不替后端宣布"没有"。
  const [d, v] = await Promise.allSettled([
    DriverApi.getSimpleDriverList(),
    VehicleApi.getSimpleVehicleList()
  ])
  if (d.status === 'fulfilled') driverOptions.value = d.value
  if (v.status === 'fulfilled') vehicleOptions.value = v.value
}

const getList = async () => {
  loading.value = true
  try {
    const res = await DriverVehicleApi.getDriverVehiclePage(queryParams)
    list.value = res.list
    total.value = res.total
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    // （队列 #68 / 台账 D-134；判据 scripts/check_admin_catch_leaves_data.py）
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有数据」）')
  } finally {
    loading.value = false
  }
}
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, driverId: undefined, status: undefined }); getList() }

// 绑定
const bindVisible = ref(false)
const bindLoading = ref(false)
const bindFormRef = ref()
const bindForm = ref<{ driverId?: number; vehicleId?: number }>({ driverId: undefined, vehicleId: undefined })
const bindRules = {
  driverId: [{ required: true, message: '请选择司机', trigger: 'change' }],
  vehicleId: [{ required: true, message: '请选择车辆', trigger: 'change' }],
}
const openBind = () => {
  bindForm.value = { driverId: undefined, vehicleId: undefined }
  bindVisible.value = true
}
const submitBind = async () => {
  const valid = await bindFormRef.value?.validate()
  if (!valid) return
  // 校验已通过但 TS 无法推断，运行时再兜底
  const { driverId, vehicleId } = bindForm.value
  if (driverId == null || vehicleId == null) {
    message.error('请选择司机与车辆')
    return
  }
  // 二次确认（队列 #67）：这个口不是"加一条绑定"这么简单 —— 后端 bind() 会先把
  // 该司机现有的有效绑定、以及该车现有的有效绑定**双双置为已解绑**，再 insert 新的一条
  // （DriverVehicleServiceImpl.bind，一司机一车 / 一车一司机）。
  // 也就是说管理员点一次就可能拆掉另一对正在用的人车关系，而且没有撤销入口 ⇒ 必须先念出被拆的是谁。
  const driverName = driverOptions.value.find((d) => d.id === driverId)?.name || `#${driverId}`
  const plateNo = vehicleOptions.value.find((v) => v.id === vehicleId)?.plateNo || `#${vehicleId}`
  try {
    await message.confirm(
      `确认把司机「${driverName}」与车辆「${plateNo}」绑定？` +
        '后端会先解掉这名司机现有的车、以及这辆车现有的司机（一司机一车），原有绑定会直接变成「已解绑」，无法在这里撤销。',
      '绑定车辆'
    )
  } catch (e) {
    return // 用户取消：什么都没发生
  }
  bindLoading.value = true
  try {
    await DriverVehicleApi.bindDriverVehicle({ driverId, vehicleId })
    message.success('绑定成功')
    bindVisible.value = false
    getList()
  } finally {
    bindLoading.value = false
  }
}
const handleUnbind = async (row: DriverVehicleVO) => {
  try {
    await message.confirm(`确认解绑 ${row.driverName} 与 ${row.plateNo} 的绑定？`)
    await DriverVehicleApi.unbindDriverVehicle(row.id)
    message.success('解绑成功')
    getList()
  } catch (e) { /* cancelled */ }
}

onMounted(() => { loadOptions(); getList() })
</script>
