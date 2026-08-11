<template>
  <Dialog title="商品订单详情" v-model="dialogVisible" width="640px">
    <el-descriptions :column="2" border v-loading="loading">
      <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="statusTag(detail.status)">{{ detail.statusName || '未知' }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="购买手机号">{{ detail.userMobile }}</el-descriptions-item>
      <el-descriptions-item label="下单时间">{{ detail.createTime }}</el-descriptions-item>
      <el-descriptions-item label="订单金额"><span style="color:#e6a23c;font-weight:600">¥{{ detail.totalAmount }}</span></el-descriptions-item>
      <el-descriptions-item label="订单备注">{{ detail.remark || '-' }}</el-descriptions-item>
      <el-descriptions-item label="收货人">{{ detail.receiverName }}</el-descriptions-item>
      <el-descriptions-item label="收货电话">{{ detail.receiverMobile }}</el-descriptions-item>
      <el-descriptions-item label="收货地址" :span="2">{{ detail.receiverAddress }}</el-descriptions-item>
    </el-descriptions>
    <el-divider content-position="left">商品明细</el-divider>
    <el-table :data="detail.items || []" border stripe size="small">
      <el-table-column label="商品" align="center">
        <template #default="scope">
          <span style="font-size:18px">{{ scope.row.productImage }}</span>
          <span style="margin-left:6px">{{ scope.row.productName }}</span>
        </template>
      </el-table-column>
      <el-table-column label="单价" align="center" width="100">
        <template #default="scope">¥{{ scope.row.productPrice }}</template>
      </el-table-column>
      <el-table-column label="数量" prop="quantity" align="center" width="80" />
      <el-table-column label="小计" align="center" width="110">
        <template #default="scope">¥{{ scope.row.amount }}</template>
      </el-table-column>
    </el-table>
  </Dialog>
</template>

<script setup lang="ts">
import * as ProductOrderApi from '@/api/transport/productOrder'
import { Dialog } from '@/components/Dialog'

const dialogVisible = ref(false)
const loading = ref(false)
const detail = ref<ProductOrderApi.ProductOrderVO>({})

const statusMap = {
  0: { tag: 'info' },
  1: { tag: 'warning' },
  2: { tag: 'success' },
  3: { tag: 'danger' }
}
const statusTag = (s: number) => (statusMap as any)[s]?.tag || 'info'

const open = async (id: number) => {
  dialogVisible.value = true
  loading.value = true
  try {
    detail.value = await ProductOrderApi.getProductOrder(id)
  } finally { loading.value = false }
}

defineExpose({ open })
</script>
