<template>
  <ContentWrap title="货物交接管理">
    <ContentWrap>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="订单编号">
          <el-input v-model="queryParams.orderId" placeholder="运输订单编号" clearable @keyup.enter="handleQuery" style="width:160px" />
        </el-form-item>
        <el-form-item label="交接站点">
          <el-input v-model="queryParams.stationId" placeholder="站点编号" clearable @keyup.enter="handleQuery" style="width:140px" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width:140px">
            <!-- 选项由 constants.ts 的后端枚举映射生成：筛的是 status，列里念的也是同一份映射，
                 两边不会再各说一套话（以前这里写死"待确认/已确认/有争议"三个自造码） -->
            <el-option
              v-for="(label, code) in HANDOVER_STATUS_LABELS"
              :key="code"
              :label="label"
              :value="Number(code)"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
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
            <el-tag :type="handoverStatusTag(scope.row.status)">{{ handoverStatusLabel(scope.row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="交接时间" prop="handoverTime" align="center" width="170" />
        <el-table-column label="确认时间" prop="confirmTime" align="center" width="170" />
        <el-table-column label="凭证" align="center" width="150">
          <template #default="scope">
            <!-- 照片是"货与舱的核验凭证"，签名是"谁认了这笔交接"：两件事，分开显示，也不共用一个缩略图位 -->
            <el-image v-if="scope.row.photoUrl" :src="scope.row.photoUrl" :preview-src-list="[scope.row.photoUrl]"
                      preview-teleported fit="cover" style="width:40px;height:40px;margin-right:6px;border-radius:4px" />
            <span v-else class="text-gray-400">没拍照</span>
            <el-image v-if="scope.row.signUrl" :src="scope.row.signUrl" :preview-src-list="[scope.row.signUrl]"
                      preview-teleported fit="contain" style="width:52px;height:40px;border-radius:4px;background:#f5f5f5" />
            <span v-else class="text-gray-400">没签名</span>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" align="center" show-overflow-tooltip />
        <el-table-column label="操作" align="center" width="140" fixed="right">
          <template #default="scope">
            <!-- 隐藏条件必须跟后端一致：`HandoverServiceImpl#disputeHandover` 只拦 COMPLETED(4)。
                 以前写的是 `status !== 2` —— 于是对 4（真会被后端拒）留着按钮，对 2（后端肯接）反而藏掉，
                 两面都是错的：前者点下去必抛，后者是界面承诺不了一次它能做的事。 -->
            <el-button
              v-if="scope.row.status !== HANDOVER_DISPUTE_BLOCKED_STATUS"
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
        <el-button type="primary" :loading="disputeLoading" @click="submitDispute">确定</el-button>
      </template>
    </Dialog>
  </ContentWrap>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import * as HandoverApi from '@/api/transport/handover'
import { Dialog } from '@/components/Dialog'
import {
  HANDOVER_DISPUTE_BLOCKED_STATUS,
  HANDOVER_STATUS_LABELS,
  handoverStatusLabel,
  handoverStatusTag
} from '../constants'

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
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"

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
// 后端 dispute 这个写口没有幂等键 ⇒ 请求在飞时按钮必须不可再点（:loading 同时禁掉点击）
const disputeLoading = ref(false)
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
  if (disputeLoading.value) return
  disputeLoading.value = true
  try {
    await HandoverApi.disputeHandover({ id: disputeForm.id!, remark: disputeForm.remark })
    message.success('已标记争议')
    disputeVisible.value = false
    getList()
  } catch (e) {
    // 错误已由请求层提示
  } finally {
    disputeLoading.value = false
  }
}

onMounted(getList)
</script>
