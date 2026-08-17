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
        <el-table-column label="货物名称" align="center" min-width="100">
          <template #default="scope">{{ scope.row.goodsName || '-' }}</template>
        </el-table-column>
        <el-table-column label="收货人" align="center" width="100">
          <template #default="scope">{{ scope.row.receiverName || '-' }}</template>
        </el-table-column>
        <el-table-column label="收货电话" align="center" width="120">
          <template #default="scope">{{ scope.row.receiverMobile || '-' }}</template>
        </el-table-column>
        <el-table-column label="寄件照" align="center" width="70">
          <template #default="scope">
            <el-image v-if="scope.row.photoUrl" :src="scope.row.photoUrl" :preview-src-list="[scope.row.photoUrl]" fit="cover" style="width:44px;height:44px;border-radius:6px" />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="司机收件照" align="center" width="70">
          <template #default="scope">
            <el-image v-if="scope.row.driverPhotoUrl" :src="scope.row.driverPhotoUrl" :preview-src-list="[scope.row.driverPhotoUrl]" fit="cover" style="width:44px;height:44px;border-radius:6px" />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="取件码" align="center" width="90">
          <template #default="scope">
            <span v-if="scope.row.pickupCode">{{ scope.row.pickupCode }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="取件状态" align="center" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.pickupStatus !== undefined && scope.row.pickupStatus !== null" :type="scope.row.pickupStatus === 1 ? 'success' : 'warning'" size="small">
              {{ scope.row.pickupStatus === 1 ? '已取件' : '待取件' }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" align="center" width="90">
          <template #default="scope">
            <el-tag v-if="scope.row.orderType === 2 && scope.row.auditStatus !== undefined && scope.row.auditStatus !== null" :type="scope.row.auditStatus === 1 ? 'success' : (scope.row.auditStatus === 2 ? 'danger' : 'warning')" size="small">
              {{ scope.row.auditStatus === 1 ? '已通过' : (scope.row.auditStatus === 2 ? '已拒绝' : '待审核') }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="订单金额" prop="totalAmount" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="200">
          <template #default="scope">
            <el-button v-if="scope.row.orderType === 2 && scope.row.auditStatus === 0" link type="warning" v-hasPermi="['transport:order:update']" @click="openAudit(scope.row)">审核</el-button>
            <el-button link type="primary" v-hasPermi="['transport:order:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:order:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>
  </ContentWrap>
  <OrderForm ref="formRef" @success="getList" />

  <!-- 货运物品审核 -->
  <Dialog v-model="auditVisible" title="审核货运物品" width="560px">
    <el-form label-width="100px">
      <el-form-item label="寄件照">
        <el-image v-if="auditRow.photoUrl" :src="auditRow.photoUrl" :preview-src-list="[auditRow.photoUrl]" fit="cover" style="width:120px;height:120px;border-radius:8px" />
        <span v-else>无</span>
      </el-form-item>
      <el-form-item label="司机收件照">
        <el-image v-if="auditRow.driverPhotoUrl" :src="auditRow.driverPhotoUrl" :preview-src-list="[auditRow.driverPhotoUrl]" fit="cover" style="width:120px;height:120px;border-radius:8px" />
        <span v-else>无</span>
      </el-form-item>
      <el-form-item label="货物信息">
        <span>{{ auditRow.goodsName || '-' }} · {{ auditRow.cargoWeightKg ? auditRow.cargoWeightKg + 'kg' : '-' }} · {{ auditRow.goodsNote || '无备注' }}</span>
      </el-form-item>
      <el-form-item label="收件信息">
        <span>{{ auditRow.receiverName || '-' }} {{ auditRow.receiverMobile || '' }}</span>
      </el-form-item>
      <el-form-item label="拒绝原因">
        <el-input v-model="auditForm.rejectReason" type="textarea" :rows="3" placeholder="拒绝时填写，如：疑似易燃易爆 / 违禁品 / 超限" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="auditVisible = false">取 消</el-button>
      <el-button type="success" :loading="auditLoading" @click="submitAudit(true)">审核通过</el-button>
      <el-button type="danger" :loading="auditLoading" @click="submitAudit(false)">拒绝运输</el-button>
    </template>
  </Dialog>
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
const typeTagMap: Record<number, 'success' | 'warning' | 'info'> = { 1: 'success', 2: 'warning', 3: 'info' }
const orderTypeTag = (type: number): 'success' | 'warning' | 'info' => typeTagMap[type] || 'info'

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
// 货运物品审核
const auditVisible = ref(false)
const auditLoading = ref(false)
const auditRow = ref<OrderApi.OrderVO>({})
const auditForm = ref<{ rejectReason: string }>({ rejectReason: '' })
const openAudit = (row: OrderApi.OrderVO) => {
  auditRow.value = row
  auditForm.value = { rejectReason: '' }
  auditVisible.value = true
}
const submitAudit = async (pass: boolean) => {
  if (!pass && !auditForm.value.rejectReason) {
    message.warning('拒绝时请填写原因')
    return
  }
  auditLoading.value = true
  try {
    await OrderApi.auditOrder({ orderId: auditRow.value.id!, pass, rejectReason: auditForm.value.rejectReason })
    message.success(pass ? '审核通过' : '已拒绝该订单')
    auditVisible.value = false
    getList()
  } finally {
    auditLoading.value = false
  }
}
onMounted(getList)
</script>
