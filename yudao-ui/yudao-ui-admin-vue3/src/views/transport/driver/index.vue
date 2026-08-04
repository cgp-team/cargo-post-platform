<template>
  <ContentWrap title="司机管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="司机姓名">
          <el-input v-model="queryParams.name" placeholder="请输入司机姓名" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="queryParams.mobile" placeholder="请输入手机号" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:driver:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增司机
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="司机姓名" prop="name" align="center" />
        <el-table-column label="手机号" prop="mobile" align="center" />
        <el-table-column label="驾驶证号" prop="licenseNo" align="center" />
        <el-table-column label="驾照到期日" prop="licenseExpireDate" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:driver:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:driver:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <DriverForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as DriverApi from '@/api/transport/driver'
import DriverForm from './DriverForm.vue'
defineOptions({ name: 'TransportDriver' })
const message = useMessage(); const loading = ref(true); const total = ref(0); const list = ref([])
const queryParams = reactive({ pageNo: 1, pageSize: 10 }); const formRef = ref()
const getList = async () => { loading.value = true; try { const res = await DriverApi.getDriverPage(queryParams); list.value = res.list; total.value = res.total } finally { loading.value = false } }
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10 }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => { try { await message.confirm('确认删除该司机？'); await DriverApi.deleteDriver(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ } }
onMounted(getList)
</script>
