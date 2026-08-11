<template>
  <ContentWrap title="商品订单管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="订单号">
          <el-input v-model="queryParams.orderNo" placeholder="请输入业务订单号" clearable @keyup.enter="getList" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="s in statusList" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>
    <ContentWrap>
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="订单号" prop="orderNo" align="center" min-width="180" />
        <el-table-column label="购买手机号" prop="userMobile" align="center" width="130" />
        <el-table-column label="金额" align="center" width="100">
          <template #default="scope">¥{{ scope.row.totalAmount }}</template>
        </el-table-column>
        <el-table-column label="状态" align="center" width="100">
          <template #default="scope">
            <el-tag :type="statusTag(scope.row.status)">{{ scope.row.statusName || statusText(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="收货人" prop="receiverName" align="center" width="100" />
        <el-table-column label="收货电话" prop="receiverMobile" align="center" width="130" />
        <el-table-column label="下单时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="180">
          <template #default="scope">
            <el-button link type="primary" v-hasPermi="['transport:product-order:query']" @click="openDetail(scope.row.id)">查看</el-button>
            <el-button
              link type="warning" v-hasPermi="['transport:product-order:ship']"
              v-if="scope.row.status === 0" @click="handleShip(scope.row.id)"
            >发货</el-button>
            <el-button
              link type="success" v-hasPermi="['transport:product-order:complete']"
              v-if="scope.row.status === 1" @click="handleComplete(scope.row.id)"
            >完成</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <ProductOrderDetail ref="detailRef" />
</template>

<script setup lang="ts">
import * as ProductOrderApi from '@/api/transport/productOrder'
import ProductOrderDetail from './ProductOrderDetail.vue'

defineOptions({ name: 'TransportProductOrder' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])
const detailRef = ref()

const statusList = [
  { value: 0, label: '待发货' },
  { value: 1, label: '已发货' },
  { value: 2, label: '已完成' },
  { value: 3, label: '已取消' }
]
const statusMap = {
  0: { text: '待发货', tag: 'info' },
  1: { text: '已发货', tag: 'warning' },
  2: { text: '已完成', tag: 'success' },
  3: { text: '已取消', tag: 'danger' }
}
const statusTag = (s: number) => (statusMap as any)[s]?.tag || 'info'
const statusText = (s: number) => (statusMap as any)[s]?.text || '未知'

type QueryParams = { pageNo: number; pageSize: number; orderNo?: string; status?: number }
const queryParams = reactive<QueryParams>({ pageNo: 1, pageSize: 10, orderNo: '', status: undefined })

const getList = async () => {
  loading.value = true
  try {
    const res = await ProductOrderApi.getProductOrderPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally { loading.value = false }
}
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, orderNo: '', status: undefined }); getList() }

const openDetail = (id: number) => detailRef.value?.open(id)

const handleShip = async (id: number) => {
  try { await message.confirm('确认发货该订单？'); await ProductOrderApi.shipProductOrder(id); message.success('发货成功'); getList() } catch (e) { /* cancelled */ }
}
const handleComplete = async (id: number) => {
  try { await message.confirm('确认该订单已完成？'); await ProductOrderApi.completeProductOrder(id); message.success('操作成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
