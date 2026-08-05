<template>
  <ContentWrap title="订单管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="订单号">
          <el-input v-model="queryParams.orderNo" placeholder="请输入订单号" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="订单类型">
          <el-select v-model="queryParams.orderType" placeholder="请选择" clearable style="width:140px">
            <el-option label="客运" :value="1" />
            <el-option label="货运" :value="2" />
            <el-option label="邮快件" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-button type="primary" v-hasPermi="['transport:order:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增订单
      </el-button>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="订单号" prop="orderNo" align="center" width="200" />
        <el-table-column label="订单类型" prop="orderType" align="center" width="80">
          <template #default="scope">
            <el-tag :type="orderTypeTag(scope.row.orderType)" size="small">
              {{ orderTypeLabel(scope.row.orderType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="取货站点" prop="pickupStationId" align="center" />
        <el-table-column label="送达站点" prop="deliveryStationId" align="center" />
        <el-table-column label="订单金额" prop="totalAmount" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="150">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:order:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:order:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <OrderForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as OrderApi from '@/api/transport/order'
import OrderForm from './OrderForm.vue'

defineOptions({ name: 'TransportOrder' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])

type OrderQueryParams = {
  pageNo: number
  pageSize: number
  orderNo?: string
  orderType?: number
}
const queryParams = reactive<OrderQueryParams>({ pageNo: 1, pageSize: 10, orderNo: '', orderType: undefined })
const formRef = ref()

const orderTypeLabel = (type: number) => ({ 1: '客运', 2: '货运', 3: '邮快件' }[type] || '未知')
const orderTypeTag = (type: number) => ({ 1: 'success', 2: 'warning', 3: 'info' }[type] || '')

const getList = async () => {
  loading.value = true
  try {
    const res = await OrderApi.getOrderPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally { loading.value = false }
}
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, orderNo: '', orderType: undefined }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try { await message.confirm('确认删除该订单？'); await OrderApi.deleteOrder(id); message.success('删除成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
