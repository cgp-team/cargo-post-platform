<template>
  <ContentWrap title="站点管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="站点编码">
          <el-input v-model="queryParams.stationCode" placeholder="请输入站点编码" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="站点名称">
          <el-input v-model="queryParams.stationName" placeholder="请输入站点名称" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:station:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增站点
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="站点编码" prop="stationCode" align="center" />
        <el-table-column label="站点名称" prop="stationName" align="center" />
        <el-table-column label="站点级别" prop="stationLevel" align="center" />
        <el-table-column label="站点地址" prop="address" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:station:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:station:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <StationForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as StationApi from '@/api/transport/station'
import StationForm from './StationForm.vue'

defineOptions({ name: 'TransportStation' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])
const queryParams = reactive({ pageNo: 1, pageSize: 10 })
const formRef = ref()

const getList = async () => {
  loading.value = true
  try {
    const res = await StationApi.getStationPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally { loading.value = false }
}

const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10 }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该站点？'); await StationApi.deleteStation(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}

onMounted(getList)
</script>
