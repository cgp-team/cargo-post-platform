<template>
  <Dialog title="商品订单详情" v-model="dialogVisible" width="720px">
    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      :title="loadError"
      style="margin-bottom:12px"
    />
    <el-descriptions :column="2" border v-loading="loading">
      <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="statusTag(detail.status)">{{ detail.statusName || '未知' }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="购买手机号">{{ detail.userMobile }}</el-descriptions-item>
      <el-descriptions-item label="下单时间">{{ detail.createTime }}</el-descriptions-item>
      <el-descriptions-item label="订单金额"><span style="color:var(--el-color-warning);font-weight:600">¥{{ detail.totalAmount }}</span></el-descriptions-item>
      <el-descriptions-item label="订单备注">{{ detail.remark || '-' }}</el-descriptions-item>
      <el-descriptions-item label="收货人">{{ detail.receiverName }}</el-descriptions-item>
      <el-descriptions-item label="收货电话">{{ detail.receiverMobile }}</el-descriptions-item>
      <el-descriptions-item label="收货地址" :span="2">{{ detail.receiverAddress }}</el-descriptions-item>
      <el-descriptions-item v-if="detail.vehicleId" label="承运车辆">{{ vehicleLabel }}</el-descriptions-item>
      <el-descriptions-item v-if="detail.shiftId" label="承运班次">{{ shiftLabelText }}</el-descriptions-item>
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
import * as VehicleApi from '@/api/transport/vehicle'
import * as ShiftApi from '@/api/transport/shift'
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
const statusTag = (s?: number) => (statusMap as any)[s ?? 0]?.tag || 'info'

const vehicleOptions = ref<VehicleApi.VehicleVO[]>([])
const shiftOptions = ref<ShiftApi.ShiftVO[]>([])
const vehicleLabel = computed(() => {
  const v = vehicleOptions.value.find((item) => item.id === detail.value.vehicleId)
  return v?.plateNo || `#${detail.value.vehicleId}`
})
const shiftLabelText = computed(() => {
  const s = shiftOptions.value.find((item) => item.id === detail.value.shiftId)
  if (!s) return `#${detail.value.shiftId}`
  return s.plannedDepartureTime ? `${s.shiftCode} ${s.plannedDepartureTime}` : s.shiftCode
})

const loadError = ref('')
const open = async (id: number) => {
  dialogVisible.value = true
  loadError.value = ''
  loading.value = true
  try {
    detail.value = await ProductOrderApi.getProductOrder(id)
    if (detail.value.vehicleId || detail.value.shiftId) {
      // 两个字典互不依赖：串成链是两个 RTT 之和，且车辆那一个抛错时班次根本不去取
      // —— 班次那格就"看着像没有班次"。并发跑，失败的那格留空。
      const [veh, shift] = await Promise.allSettled([
        VehicleApi.getSimpleVehicleList(),
        ShiftApi.getSimpleShiftList()
      ])
      if (veh.status === 'fulfilled') vehicleOptions.value = veh.value
      if (shift.status === 'fulfilled') shiftOptions.value = shift.value
    }
  } catch (e: any) {
    // 读失败时下面那格念的可能是上一次打开的那条 ⇒ 点名「这次」没读到
    loadError.value = '这条商品订单没读到：' + (e?.msg || e?.message || '未知原因') + '（下面念的不一定是这一条的）'
  } finally { loading.value = false }
}

defineExpose({ open })
</script>
