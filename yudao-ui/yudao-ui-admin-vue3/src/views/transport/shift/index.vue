<template>
  <ContentWrap title="班次管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="班次编码">
          <el-input v-model="queryParams.shiftCode" placeholder="请输入班次编码" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:shift:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增班次
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="班次编码" prop="shiftCode" align="center" />
        <el-table-column label="线路编号" prop="routeId" align="center" />
        <el-table-column label="发车时间" prop="plannedDepartureTime" align="center" />
        <el-table-column label="计划时长(分钟)" prop="plannedDurationMinutes" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:shift:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:shift:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无班次，点击下方按钮排第一个班次">
            <el-button type="primary" @click="openForm('create')">新增班次</el-button>
          </el-empty>
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <ShiftForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as ShiftApi from '@/api/transport/shift'
import ShiftForm from './ShiftForm.vue'

defineOptions({ name: 'TransportShift' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])

type ShiftQueryParams = {
  pageNo: number
  pageSize: number
  shiftCode?: string
}
const queryParams = reactive<ShiftQueryParams>({ pageNo: 1, pageSize: 10, shiftCode: '' })
const formRef = ref()

const getList = async () => {
  loading.value = true
  try {
    const res = await ShiftApi.getShiftPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally { loading.value = false }
}
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, shiftCode: '' }); getList() } // WEB-22: 清空全部查询字段
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该班次？'); await ShiftApi.deleteShift(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
