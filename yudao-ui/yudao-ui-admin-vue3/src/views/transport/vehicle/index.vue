<template>
  <ContentWrap title="车辆管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="车牌号">
          <el-input v-model="queryParams.plateNo" placeholder="请输入车牌号" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:vehicle:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增车辆
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="车牌号" prop="plateNo" align="center" />
        <el-table-column label="车辆类型" prop="vehicleType" align="center" />
        <el-table-column label="载客人数" prop="passengerCapacity" align="center" />
        <el-table-column label="载货重量(kg)" prop="cargoCapacityKg" align="center" />
        <el-table-column label="货仓件数" prop="cargoCapacity" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:vehicle:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:vehicle:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <VehicleForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as VehicleApi from '@/api/transport/vehicle'
import VehicleForm from './VehicleForm.vue'
defineOptions({ name: 'TransportVehicle' })
const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])
type VehicleQueryParams = {
  pageNo: number
  pageSize: number
  plateNo?: string
}

const queryParams = reactive<VehicleQueryParams>({
  pageNo: 1,
  pageSize: 10,
  plateNo: '',
})
const formRef = ref()
const getList = async () => {
  loading.value = true
  try { const res = await VehicleApi.getVehiclePage(queryParams); list.value = res.list; total.value = res.total } finally { loading.value = false }
}
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10 }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该车辆？'); await VehicleApi.deleteVehicle(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
