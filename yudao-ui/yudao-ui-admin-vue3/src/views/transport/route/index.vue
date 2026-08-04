<template>
  <ContentWrap title="线路管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="线路编码">
          <el-input v-model="queryParams.routeCode" placeholder="请输入线路编码" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="线路名称">
          <el-input v-model="queryParams.routeName" placeholder="请输入线路名称" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:route:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增线路
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="线路编码" prop="routeCode" align="center" />
        <el-table-column label="线路名称" prop="routeName" align="center" />
        <el-table-column label="起点站点ID" prop="startStationId" align="center" />
        <el-table-column label="终点站点ID" prop="endStationId" align="center" />
        <el-table-column label="里程(km)" prop="distanceKm" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:route:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:route:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <RouteForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as RouteApi from '@/api/transport/route'
import RouteForm from './RouteForm.vue'
defineOptions({ name: 'TransportRoute' })
const message = useMessage(); const loading = ref(true); const total = ref(0); const list = ref([])
type RouteQueryParams = {
  pageNo: number
  pageSize: number
  routeCode?: string
  routeName?: string
}

const queryParams = reactive<RouteQueryParams>({
  pageNo: 1,
  pageSize: 10,
  routeCode: '',
  routeName: '',
}); const formRef = ref()
const getList = async () => { loading.value = true; try { const res = await RouteApi.getRoutePage(queryParams); list.value = res.list; total.value = res.total } finally { loading.value = false } }
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10 }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => { try { await message.confirm('确认删除该线路？'); await RouteApi.deleteRoute(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ } }
onMounted(getList)
</script>
