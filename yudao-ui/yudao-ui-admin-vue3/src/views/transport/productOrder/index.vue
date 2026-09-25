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
        <!-- 司机执行闭环：承运司机 / 交付站点 / 装车核验凭证 / 妥投凭证 -->
        <el-table-column label="承运车辆/司机" align="center" min-width="150">
          <template #default="scope">
            <div>{{ scope.row.vehiclePlate || '-' }}</div>
            <div class="text-gray-500 text-xs">{{ scope.row.driverName || '未派司机' }}{{ scope.row.driverMobile ? ' · ' + scope.row.driverMobile : '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="交付站点" align="center" min-width="130">
          <template #default="scope">{{ scope.row.deliverStationName || '-' }}</template>
        </el-table-column>
        <el-table-column label="装车核验" align="center" width="100">
          <template #default="scope">
            <el-image
              v-if="scope.row.loadPhotoUrl"
              :src="scope.row.loadPhotoUrl"
              :preview-src-list="[scope.row.loadPhotoUrl]"
              fit="cover"
              style="width:44px;height:44px;border-radius:6px"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="妥投凭证" align="center" width="100">
          <template #default="scope">
            <el-image
              v-if="scope.row.deliverPhotoUrl"
              :src="scope.row.deliverPhotoUrl"
              :preview-src-list="[scope.row.deliverPhotoUrl]"
              fit="cover"
              style="width:44px;height:44px;border-radius:6px"
            />
            <span v-else>-</span>
          </template>
        </el-table-column>
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
      
        <template #empty>
          <el-empty :image-size="60" description="暂无商城订单" />
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <ProductOrderDetail ref="detailRef" />
  <ProductOrderShipForm ref="shipFormRef" @success="getList" />
</template>

<script setup lang="ts">
import * as ProductOrderApi from '@/api/transport/productOrder'
import {
  PRODUCT_ORDER_STATUS_LABELS,
  productOrderStatusLabel,
  productOrderStatusTag
} from '../constants'
import ProductOrderDetail from './ProductOrderDetail.vue'
import ProductOrderShipForm from './ProductOrderShipForm.vue'

defineOptions({ name: 'TransportProductOrder' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref([])
const detailRef = ref()

// WEB-09: 状态映射统一消费 constants.ts（PRODUCT_ORDER_STATUS_LABELS 与后端 ProductOrderStatusEnum 对齐）
const statusList = Object.entries(PRODUCT_ORDER_STATUS_LABELS).map(([value, label]) => ({
  value: Number(value),
  label
}))
const statusTag = (s: number) => productOrderStatusTag(s)
const statusText = (s: number) => productOrderStatusLabel(s)

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

const shipFormRef = ref()
const handleShip = (id: number) => shipFormRef.value?.open(id)
const handleComplete = async (id: number) => {
  try { await message.confirm('确认该订单已完成？'); await ProductOrderApi.completeProductOrder(id); message.success('操作成功'); getList() } catch (e) { /* cancelled */ }
}
onMounted(getList)
</script>
