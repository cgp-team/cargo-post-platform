<template>
  <ContentWrap title="货物交接管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="getList">
        <el-form-item label="订单编号">
          <el-input v-model="queryParams.orderId" placeholder="运输订单编号" clearable @keyup.enter="getList" style="width:160px" />
        </el-form-item>
        <el-form-item label="交接站点">
          <el-input v-model="queryParams.stationId" placeholder="站点编号" clearable @keyup.enter="getList" style="width:140px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:140px">
            <el-option label="待确认" :value="0" />
            <el-option label="已确认" :value="1" />
            <el-option label="有争议" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </ContentWrap>

    <ContentWrap>
      <el-table v-loading="loading" :data="list" stripe border>
        <el-table-column label="交接编号" prop="id" align="center" width="90" />
        <el-table-column label="订单号" prop="orderNo" align="center" width="140" show-overflow-tooltip />
        <el-table-column label="换乘站" prop="stationName" align="center" width="130" show-overflow-tooltip />
        <el-table-column label="交出司机" prop="fromDriverName" align="center" width="110" />
        <el-table-column label="接收司机" prop="toDriverName" align="center" width="110" />
        <el-table-column label="件数" prop="itemCount" align="center" width="70" />
        <el-table-column label="重量(kg)" prop="weightKg" align="center" width="90" />
        <el-table-column label="状态" prop="status" align="center" width="100">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)">{{ scope.row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="交接时间" prop="handoverTime" align="center" width="170" />
        <el-table-column label="确认时间" prop="confirmTime" align="center" width="170" />
        <el-table-column label="备注" prop="remark" align="center" show-overflow-tooltip />
        <el-table-column label="操作" align="center" width="140" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.status !== 2"
              link
              type="warning"
              v-hasPermi="['transport:handover:update']"
              @click="openDispute(scope.row)"
            >标记争议</el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="未找到交接单，试试调整筛选条件" />
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </ContentWrap>

    <!-- WEB-07: 改回 Dialog 封装（复用统一头部/底部与关闭行为） -->
    <Dialog v-model="disputeVisible" title="标记交接争议" width="480px">
      <el-form ref="disputeFormRef" :model="disputeForm" :rules="disputeRules" label-width="80px">
        <el-form-item label="争议原因" prop="remark">
          <el-input v-model="disputeForm.remark" type="textarea" :rows="3" placeholder="如：件数不符 / 货物破损" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="disputeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitDispute">确定</el-button>
      </template>
    </Dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import * as HandoverApi from '@/api/transport/handover'
import { Dialog } from '@/components/Dialog'

defineOptions({ name: 'TransportHandover' })

const message = useMessage()
const loading = ref(true)
const total = ref(0)
const list = ref<HandoverApi.HandoverVO[]>([])
const queryParams = reactive<{ pageNo: number; pageSize: number; orderId?: number; stationId?: number; status?: number }>({
  pageNo: 1,
  pageSize: 10,
  orderId: undefined,
  stationId: undefined,
  status: undefined
})

const disputeVisible = ref(false)
const disputeForm = reactive<{ id?: number; remark: string }>({ remark: '' })

const statusTagType = (status?: number) => {
  if (status === 1) return 'success'
  if (status === 2) return 'danger'
  return 'warning'
}

const getList = async () => {
  loading.value = true
  try {
    const res = await HandoverApi.getHandoverPage(queryParams)
    list.value = res.list
    total.value = res.total
  } finally {
    loading.value = false
  }
}

const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, orderId: undefined, stationId: undefined, status: undefined })
  getList()
}

const openDispute = (row: HandoverApi.HandoverVO) => {
  disputeForm.id = row.id
  disputeForm.remark = ''
  disputeVisible.value = true
}

// WEB-16: 字段级校验；WEB-17: 标记争议为风险操作，提交前二次确认
const disputeRules = {
  remark: [{ required: true, message: '请填写争议原因', trigger: 'blur' }]
}
const disputeFormRef = ref()
const submitDispute = async () => {
  try { await disputeFormRef.value?.validate() } catch { return }
  try {
    await message.confirm('确认标记该交接单为争议？标记后将进入争议处理流程。')
  } catch {
    return
  }
  if (!disputeForm.remark) {
    message.warning('请填写争议原因')
    return
  }
  try {
    await HandoverApi.disputeHandover({ id: disputeForm.id!, remark: disputeForm.remark })
    message.success('已标记争议')
    disputeVisible.value = false
    getList()
  } catch (e) {
    // 错误已由请求层提示
  }
}

onMounted(getList)
</script>
