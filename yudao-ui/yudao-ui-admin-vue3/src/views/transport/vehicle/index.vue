<template>
  <ContentWrap title="车辆管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="车牌号">
          <el-input v-model="queryParams.plateNo" placeholder="请输入车牌号" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择" clearable style="width:120px">
            <el-option label="可用" :value="0" />
            <el-option label="停用" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:vehicle:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增车辆
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
        <el-table-column label="车牌号" prop="plateNo" align="center" />
        <el-table-column label="车辆类型" prop="vehicleType" align="center" />
        <el-table-column label="载客人数" prop="passengerCapacity" align="center" />
        <el-table-column label="载货重量(kg)" prop="cargoCapacityKg" align="center" />
        <el-table-column label="货仓件数" prop="cargoCapacity" align="center" />
        <el-table-column label="状态" prop="status" align="center" width="80">
          <template #default="scope">
            <el-tag :type="vehicleStatusTag(scope.row.status)" size="small">
              {{ vehicleStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="实时状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="vehicleRealtimeStatusTag(scope.row.realtimeStatus)" size="small">
              {{ vehicleRealtimeStatusLabel(scope.row.realtimeStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:vehicle:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:vehicle:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无车辆，点击下方按钮登记第一辆车">
            <el-button type="primary" @click="openForm('create')">新增车辆</el-button>
          </el-empty>
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <VehicleForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as VehicleApi from '@/api/transport/vehicle'
import {
  vehicleStatusLabel,
  vehicleStatusTag,
  vehicleRealtimeStatusLabel,
  vehicleRealtimeStatusTag
} from '../constants'
import VehicleForm from './VehicleForm.vue'
defineOptions({ name: 'TransportVehicle' })
const message = useMessage()
// WEB-09: 车辆状态/实时状态映射统一消费 constants.ts（本地映射已删）
const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref([])
type VehicleQueryParams = {
  pageNo: number
  pageSize: number
  plateNo?: string
  status?: number
}

const queryParams = reactive<VehicleQueryParams>({
  pageNo: 1,
  pageSize: 10,
  plateNo: '',
  status: undefined,
})
const formRef = ref()
const getList = async () => {
  loading.value = true
  try { const res = await VehicleApi.getVehiclePage(queryParams); list.value = res.list; total.value = res.total } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    // （队列 #68 / 台账 D-134；判据 scripts/check_admin_catch_leaves_data.py）
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有数据」）')
  } finally { loading.value = false }
}
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, plateNo: '', status: undefined }); getList() } // WEB-22: 清空全部查询字段
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该车辆？'); await VehicleApi.deleteVehicle(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
