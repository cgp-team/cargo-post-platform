<template>
  <ContentWrap title="调度工作台">
    <!-- 订单池 -->
    <ContentWrap title="订单池">
      <el-form :inline="true" :model="poolQuery" @submit.prevent="getPoolList">
        <el-form-item label="订单状态">
          <el-select v-model="poolQuery.status" placeholder="请选择" clearable style="width:140px">
            <el-option label="待调度" :value="0" />
            <el-option label="已入池" :value="1" />
            <el-option label="已分配" :value="2" />
            <el-option label="已发车" :value="3" />
            <el-option label="已完成" :value="4" />
            <el-option label="已取消" :value="5" />
          </el-select>
        </el-form-item>
        <el-form-item label="订单类型">
          <el-select v-model="poolQuery.orderType" placeholder="请选择" clearable style="width:140px">
            <el-option label="客运" :value="1" />
            <el-option label="货运" :value="2" />
            <el-option label="邮快件" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getPoolList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetPoolQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
      <el-button type="primary" v-hasPermi="['transport:dispatch:collect']" @click="openCollect"><Icon icon="ep:download" />归集入池</el-button>
      <el-button type="primary" v-hasPermi="['transport:dispatch:manual-plan']" :disabled="selectedOrders.length === 0" @click="openManual">
        <Icon icon="ep:pointer" />手工派单
      </el-button>
      <el-button type="primary" v-hasPermi="['transport:dispatch:smart-plan']" @click="openSmart"><Icon icon="ep:magic-stick" />智能派单</el-button>
      <el-table
        ref="poolTableRef"
        v-loading="poolLoading"
        :data="poolList"
        stripe
        border
        style="margin-top:16px"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" align="center" :selectable="poolSelectable" />
        <el-table-column label="订单号" prop="orderNo" align="center" width="200" />
        <el-table-column label="订单类型" prop="orderType" align="center" width="80">
          <template #default="scope">
            <el-tag :type="orderTypeTag(scope.row.orderType)" size="small">
              {{ orderTypeLabel(scope.row.orderType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订单状态" prop="status" align="center" width="90">
          <template #default="scope">
            <el-tag :type="orderStatusTag(scope.row.status)" size="small">
              {{ orderStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="取货站点" prop="pickupStationId" align="center">
          <template #default="scope">{{ stationName(scope.row.pickupStationId) }}</template>
        </el-table-column>
        <el-table-column label="送达站点" prop="deliveryStationId" align="center">
          <template #default="scope">{{ stationName(scope.row.deliveryStationId) }}</template>
        </el-table-column>
        <el-table-column label="订单金额" prop="totalAmount" align="center" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
      </el-table>
      <Pagination :total="poolTotal" v-model:page="poolQuery.pageNo" v-model:limit="poolQuery.pageSize" @pagination="getPoolList" />
    </ContentWrap>

    <!-- 调度方案 -->
    <ContentWrap title="调度方案" style="margin-top:16px">
      <el-form :inline="true" :model="planQuery" @submit.prevent="getPlanList">
        <el-form-item label="方案状态">
          <el-select v-model="planQuery.status" placeholder="请选择" clearable style="width:140px">
            <el-option label="待审核" :value="0" />
            <el-option label="已下发" :value="1" />
            <el-option label="执行中" :value="2" />
            <el-option label="已完成" :value="3" />
            <el-option label="已作废" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="派单方式">
          <el-select v-model="planQuery.mode" placeholder="请选择" clearable style="width:140px">
            <el-option label="手工" :value="0" />
            <el-option label="智能" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="getPlanList"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetPlanQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="planLoading" :data="planList" stripe border style="margin-top:16px">
        <el-table-column label="方案号" prop="id" align="center" width="80" />
        <el-table-column label="派单方式" prop="mode" align="center" width="90">
          <template #default="scope">
            <el-tag :type="modeTag(scope.row.mode)" size="small">{{ modeLabel(scope.row.mode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="方案状态" prop="status" align="center" width="90">
          <template #default="scope">
            <el-tag :type="planStatusTag(scope.row.status)" size="small">
              {{ planStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="总里程" prop="totalDistance" align="center" />
        <el-table-column label="算法版本" prop="algorithmVersion" align="center" />
        <el-table-column label="审核人" prop="approvedBy" align="center" />
        <el-table-column label="审核时间" prop="approvedTime" align="center" width="180" />
        <el-table-column label="创建时间" prop="createTime" align="center" width="180" />
        <el-table-column label="操作" align="center" width="200" fixed="right">
          <template #default="scope">
            <el-button link type="primary" v-if="scope.row.status === 0" v-hasPermi="['transport:dispatch:review']" @click="openReview(scope.row)">审核</el-button>
            <el-button link type="primary" v-if="scope.row.status === 1 || scope.row.status === 2" v-hasPermi="['transport:dispatch:check']" @click="openCheck(scope.row)">
              发车核验
            </el-button>
            <el-button link type="primary" @click="openDetail(scope.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="planTotal" v-model:page="planQuery.pageNo" v-model:limit="planQuery.pageSize" @pagination="getPlanList" />
    </ContentWrap>
  </ContentWrap>

  <!-- 归集入池弹窗 -->
  <Dialog title="归集入池" v-model="collectVisible" width="500px">
    <el-form ref="collectFormRef" :model="collectForm" :rules="collectRules" label-width="120px" v-loading="collectLoading">
      <el-form-item label="批次开始时间" prop="batchStart">
        <el-date-picker v-model="collectForm.batchStart" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
      </el-form-item>
      <el-form-item label="批次结束时间" prop="batchEnd">
        <el-date-picker v-model="collectForm.batchEnd" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="collectVisible = false">取 消</el-button>
      <el-button type="primary" :loading="collectLoading" @click="submitCollect">确 定</el-button>
    </template>
  </Dialog>

  <!-- 手工派单弹窗 -->
  <Dialog title="手工派单" v-model="manualVisible" width="500px">
    <el-form ref="manualFormRef" :model="manualForm" :rules="manualRules" label-width="120px" v-loading="manualLoading">
      <el-form-item label="已选订单">
        <span>{{ selectedOrders.length }} 条</span>
      </el-form-item>
      <el-form-item label="场站" prop="depotStationId">
        <el-select v-model="manualForm.depotStationId" placeholder="请选择场站" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="车辆" prop="vehicleId">
        <el-select v-model="manualForm.vehicleId" placeholder="请选择车辆" style="width:100%">
          <el-option v-for="v in vehicleList" :key="v.id!" :label="v.plateNo" :value="v.id!" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="manualVisible = false">取 消</el-button>
      <el-button type="primary" :loading="manualLoading" @click="submitManual">确 定</el-button>
    </template>
  </Dialog>

  <!-- 智能派单弹窗 -->
  <Dialog title="智能派单" v-model="smartVisible" width="500px">
    <el-form ref="smartFormRef" :model="smartForm" :rules="smartRules" label-width="120px" v-loading="smartLoading">
      <el-form-item label="场站" prop="depotStationId">
        <el-select v-model="smartForm.depotStationId" placeholder="请选择场站" style="width:100%">
          <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="可用车辆" prop="vehicleIds">
        <el-select v-model="smartForm.vehicleIds" placeholder="请选择车辆(最多 3 台)" multiple :multiple-limit="3" style="width:100%">
          <el-option v-for="v in vehicleList" :key="v.id!" :label="v.plateNo" :value="v.id!" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="smartVisible = false">取 消</el-button>
      <el-button type="primary" :loading="smartLoading" @click="submitSmart">确 定</el-button>
    </template>
  </Dialog>

  <!-- 方案审核弹窗 -->
  <Dialog title="方案审核" v-model="reviewVisible" width="500px">
    <el-form ref="reviewFormRef" :model="reviewForm" :rules="reviewRules" label-width="120px" v-loading="reviewLoading">
      <el-form-item label="审核结论" prop="approve">
        <el-radio-group v-model="reviewForm.approve">
          <el-radio :value="true">通过</el-radio>
          <el-radio :value="false">驳回</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="驳回原因" prop="reason">
        <el-input v-model="reviewForm.reason" type="textarea" :rows="3" placeholder="驳回时必填" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reviewVisible = false">取 消</el-button>
      <el-button type="primary" :loading="reviewLoading" @click="submitReview">确 定</el-button>
    </template>
  </Dialog>

  <!-- 发车核验弹窗 -->
  <Dialog title="发车核验" v-model="checkVisible" width="500px">
    <el-form ref="checkFormRef" :model="checkForm" :rules="checkRules" label-width="120px" v-loading="checkLoading">
      <el-form-item label="车辆" prop="vehicleId">
        <el-select v-model="checkForm.vehicleId" placeholder="请选择车辆" style="width:100%">
          <el-option v-for="v in checkVehicles" :key="v.id!" :label="v.plateNo" :value="v.id!" />
        </el-select>
      </el-form-item>
      <el-form-item label="核验结论" prop="pass">
        <el-radio-group v-model="checkForm.pass">
          <el-radio :value="true">通过</el-radio>
          <el-radio :value="false">不通过</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="备注" prop="remark">
        <el-input v-model="checkForm.remark" type="textarea" :rows="3" placeholder="请输入备注" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="checkVisible = false">取 消</el-button>
      <el-button type="primary" :loading="checkLoading" @click="submitCheck">确 定</el-button>
    </template>
  </Dialog>

  <!-- 方案详情弹窗 -->
  <Dialog :title="`方案详情(方案号:${detail?.id ?? '-'})`" v-model="detailVisible" width="900px">
    <el-table v-loading="detailLoading" :data="detailItems" stripe border>
      <el-table-column label="经停顺序" prop="visitSequence" align="center" width="80" />
      <el-table-column label="车辆" prop="vehicleId" align="center">
        <template #default="scope">{{ vehicleName(scope.row.vehicleId) }}</template>
      </el-table-column>
      <el-table-column label="站点" prop="stationId" align="center">
        <template #default="scope">{{ stationName(scope.row.stationId) }}</template>
      </el-table-column>
      <el-table-column label="动作" prop="actionType" align="center" width="90">
        <template #default="scope">
          <el-tag :type="actionTag(scope.row.actionType)" size="small">{{ actionLabel(scope.row.actionType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="订单号" prop="orderId" align="center" />
      <el-table-column label="预计到达时间" prop="estimatedArrivalTime" align="center" width="180" />
    </el-table>
  </Dialog>
</template>

<script setup lang="ts">
import * as DispatchApi from '@/api/transport/dispatch'
import * as StationApi from '@/api/transport/station'
import * as VehicleApi from '@/api/transport/vehicle'
import { Dialog } from '@/components/Dialog'
import { formatDate } from '@/utils/formatTime'

defineOptions({ name: 'TransportDispatch' })

const message = useMessage()

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

// 订单类型:1 客运 2 货运 3 邮快件
const orderTypeLabelMap: Record<number, string> = { 1: '客运', 2: '货运', 3: '邮快件' }
const orderTypeLabel = (type?: number) => (type === undefined ? '-' : orderTypeLabelMap[type] || '未知')
const orderTypeTagMap: Record<number, TagType> = { 1: 'success', 2: 'warning', 3: 'info' }
const orderTypeTag = (type?: number): TagType => (type === undefined ? 'info' : orderTypeTagMap[type] || 'info')

// 订单状态:0 待调度 1 已入池 2 已分配 3 已发车 4 已完成 5 已取消
const orderStatusLabelMap: Record<number, string> = { 0: '待调度', 1: '已入池', 2: '已分配', 3: '已发车', 4: '已完成', 5: '已取消' }
const orderStatusLabel = (status?: number) => (status === undefined ? '-' : orderStatusLabelMap[status] || '未知')
const orderStatusTagMap: Record<number, TagType> = { 0: 'info', 1: 'warning', 2: 'primary', 3: 'success', 4: 'success', 5: 'danger' }
const orderStatusTag = (status?: number): TagType => (status === undefined ? 'info' : orderStatusTagMap[status] || 'info')

// 方案状态:0 待审核 1 已下发 2 执行中 3 已完成 4 已作废
const planStatusLabelMap: Record<number, string> = { 0: '待审核', 1: '已下发', 2: '执行中', 3: '已完成', 4: '已作废' }
const planStatusLabel = (status?: number) => (status === undefined ? '-' : planStatusLabelMap[status] || '未知')
const planStatusTagMap: Record<number, TagType> = { 0: 'warning', 1: 'primary', 2: 'success', 3: 'info', 4: 'danger' }
const planStatusTag = (status?: number): TagType => (status === undefined ? 'info' : planStatusTagMap[status] || 'info')

// 派单方式:0 手工 1 智能
const modeLabel = (mode?: number) => (mode === undefined ? '-' : mode === 1 ? '智能' : '手工')
const modeTag = (mode?: number): TagType => (mode === 1 ? 'success' : 'info')

// 经停动作:0 出发 1 接客 2 送客 3 派送 4 揽收 5 返回
const actionLabelMap: Record<number, string> = { 0: '出发', 1: '接客', 2: '送客', 3: '派送', 4: '揽收', 5: '返回' }
const actionLabel = (type?: number) => (type === undefined ? '-' : actionLabelMap[type] || '未知')
const actionTagMap: Record<number, TagType> = { 0: 'info', 1: 'success', 2: 'warning', 3: 'primary', 4: 'primary', 5: 'info' }
const actionTag = (type?: number): TagType => (type === undefined ? 'info' : actionTagMap[type] || 'info')

// 站点/车辆精简列表
const stationList = ref<StationApi.StationVO[]>([])
const vehicleList = ref<VehicleApi.VehicleVO[]>([])
const stationName = (id?: number) => (id === undefined ? '-' : stationList.value.find((s) => s.id === id)?.stationName ?? id)
const vehicleName = (id?: number) => (id === undefined ? '-' : vehicleList.value.find((v) => v.id === id)?.plateNo ?? id)
const loadSimpleLists = async () => {
  try {
    stationList.value = await StationApi.getSimpleStationList()
  } catch (e) { /* ignore */ }
  try {
    vehicleList.value = await VehicleApi.getSimpleVehicleList()
  } catch (e) { /* ignore */ }
}

/** 订单池 */
const poolLoading = ref(true)
const poolTotal = ref(0)
const poolList = ref<DispatchApi.DispatchOrderVO[]>([])
const selectedOrders = ref<DispatchApi.DispatchOrderVO[]>([])
const poolTableRef = ref()

type PoolQueryParams = {
  pageNo: number
  pageSize: number
  status?: number
  orderType?: number
}
const poolQuery = reactive<PoolQueryParams>({ pageNo: 1, pageSize: 10, status: 1, orderType: undefined })

const getPoolList = async () => {
  poolLoading.value = true
  try {
    const res = await DispatchApi.getDispatchPoolPage(poolQuery)
    poolList.value = res.list
    poolTotal.value = res.total
  } finally {
    poolLoading.value = false
  }
}
const resetPoolQuery = () => {
  Object.assign(poolQuery, { pageNo: 1, pageSize: 10, status: 1, orderType: undefined })
  getPoolList()
}
// 仅已入池订单可勾选参与手工派单
const poolSelectable = (row: DispatchApi.DispatchOrderVO) => row.status === 1
const handleSelectionChange = (rows: DispatchApi.DispatchOrderVO[]) => {
  selectedOrders.value = rows
}

/** 调度方案 */
const planLoading = ref(true)
const planTotal = ref(0)
const planList = ref<DispatchApi.DispatchPlanVO[]>([])

type PlanQueryParams = {
  pageNo: number
  pageSize: number
  status?: number
  mode?: number
}
const planQuery = reactive<PlanQueryParams>({ pageNo: 1, pageSize: 10, status: undefined, mode: undefined })

const getPlanList = async () => {
  planLoading.value = true
  try {
    const res = await DispatchApi.getDispatchPlanPage(planQuery)
    planList.value = res.list
    planTotal.value = res.total
  } finally {
    planLoading.value = false
  }
}
const resetPlanQuery = () => {
  Object.assign(planQuery, { pageNo: 1, pageSize: 10, status: undefined, mode: undefined })
  getPlanList()
}

/** 归集入池 */
const collectVisible = ref(false)
const collectLoading = ref(false)
const collectFormRef = ref()
const collectForm = ref({ batchStart: '', batchEnd: '' })
const collectRules = reactive({
  batchStart: [{ required: true, message: '请选择批次开始时间', trigger: 'change' }],
  batchEnd: [{ required: true, message: '请选择批次结束时间', trigger: 'change' }],
})
const openCollect = () => {
  // 默认当前半小时批次:分 < 30 取 :00-:30,否则取 :30-下一小时 :00
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), now.getMinutes() < 30 ? 0 : 30, 0)
  const end = new Date(start.getTime() + 30 * 60 * 1000)
  collectForm.value = { batchStart: formatDate(start), batchEnd: formatDate(end) }
  collectVisible.value = true
}
const submitCollect = async () => {
  const valid = await collectFormRef.value?.validate()
  if (!valid) return
  collectLoading.value = true
  try {
    const count = await DispatchApi.collectOrders(collectForm.value)
    message.success(`归集完成,共入池 ${count} 条订单`)
    collectVisible.value = false
    getPoolList()
  } finally {
    collectLoading.value = false
  }
}

/** 手工派单 */
const manualVisible = ref(false)
const manualLoading = ref(false)
const manualFormRef = ref()
const manualForm = ref<{ depotStationId?: number; vehicleId?: number }>({
  depotStationId: undefined,
  vehicleId: undefined,
})
const manualRules = reactive({
  depotStationId: [{ required: true, message: '请选择场站', trigger: 'change' }],
  vehicleId: [{ required: true, message: '请选择车辆', trigger: 'change' }],
})
const openManual = () => {
  manualForm.value = { depotStationId: undefined, vehicleId: undefined }
  manualVisible.value = true
}
const submitManual = async () => {
  const valid = await manualFormRef.value?.validate()
  if (!valid) return
  manualLoading.value = true
  try {
    const planId = await DispatchApi.createManualPlan({
      depotStationId: manualForm.value.depotStationId!,
      vehicleId: manualForm.value.vehicleId!,
      orderIds: selectedOrders.value.map((o) => o.id!),
    })
    message.success(`手工派单成功,方案号:${planId}`)
    manualVisible.value = false
    poolTableRef.value?.clearSelection()
    getPoolList()
    getPlanList()
  } finally {
    manualLoading.value = false
  }
}

/** 智能派单 */
const smartVisible = ref(false)
const smartLoading = ref(false)
const smartFormRef = ref()
const smartForm = ref<{ depotStationId?: number; vehicleIds: number[] }>({
  depotStationId: undefined,
  vehicleIds: [],
})
const smartRules = reactive({
  depotStationId: [{ required: true, message: '请选择场站', trigger: 'change' }],
  vehicleIds: [{ required: true, type: 'array', min: 1, message: '请选择车辆', trigger: 'change' }],
})
const openSmart = () => {
  smartForm.value = { depotStationId: undefined, vehicleIds: [] }
  smartVisible.value = true
}
const submitSmart = async () => {
  const valid = await smartFormRef.value?.validate()
  if (!valid) return
  smartLoading.value = true
  // 失败时由框架统一弹出后端错误信息,弹窗保持打开可再次提交
  try {
    const planId = await DispatchApi.createSmartPlan({
      depotStationId: smartForm.value.depotStationId!,
      vehicleIds: smartForm.value.vehicleIds,
    })
    message.success(`智能派单成功,方案号:${planId}`)
    smartVisible.value = false
    getPlanList()
  } finally {
    smartLoading.value = false
  }
}

/** 方案审核 */
const reviewVisible = ref(false)
const reviewLoading = ref(false)
const reviewFormRef = ref()
const reviewForm = ref<{ planId: number; approve: boolean; reason: string }>({ planId: 0, approve: true, reason: '' })
const validateReason = (_rule: any, value: string, callback: any) => {
  if (!reviewForm.value.approve && !value) callback(new Error('驳回原因不能为空'))
  else callback()
}
const reviewRules = reactive({
  approve: [{ required: true, message: '请选择审核结论', trigger: 'change' }],
  reason: [{ validator: validateReason, trigger: 'blur' }],
})
const openReview = (row: DispatchApi.DispatchPlanVO) => {
  reviewForm.value = { planId: row.id!, approve: true, reason: '' }
  reviewVisible.value = true
}
const submitReview = async () => {
  const valid = await reviewFormRef.value?.validate()
  if (!valid) return
  reviewLoading.value = true
  try {
    await DispatchApi.reviewDispatchPlan(reviewForm.value)
    message.success(reviewForm.value.approve ? '审核通过' : '已驳回')
    reviewVisible.value = false
    getPlanList()
  } finally {
    reviewLoading.value = false
  }
}

/** 发车核验 */
const checkVisible = ref(false)
const checkLoading = ref(false)
const checkFormRef = ref()
const checkForm = ref<{ planId: number; vehicleId?: number; pass: boolean; remark: string }>({
  planId: 0,
  vehicleId: undefined,
  pass: true,
  remark: '',
})
const checkVehicleIds = ref<number[]>([])
// 车辆下拉限定为该方案明细中涉及的车辆
const checkVehicles = computed(() => vehicleList.value.filter((v) => checkVehicleIds.value.includes(v.id!)))
const checkRules = reactive({
  vehicleId: [{ required: true, message: '请选择车辆', trigger: 'change' }],
  pass: [{ required: true, message: '请选择核验结论', trigger: 'change' }],
})
const openCheck = async (row: DispatchApi.DispatchPlanVO) => {
  checkForm.value = { planId: row.id!, vehicleId: undefined, pass: true, remark: '' }
  checkVehicleIds.value = []
  checkVisible.value = true
  checkLoading.value = true
  try {
    const plan = await DispatchApi.getDispatchPlan(row.id!)
    const ids = (plan.items ?? [])
      .map((item) => item.vehicleId)
      .filter((id): id is number => id !== undefined)
    checkVehicleIds.value = [...new Set(ids)]
    if (checkVehicleIds.value.length === 1) checkForm.value.vehicleId = checkVehicleIds.value[0]
  } finally {
    checkLoading.value = false
  }
}
const submitCheck = async () => {
  const valid = await checkFormRef.value?.validate()
  if (!valid) return
  checkLoading.value = true
  try {
    await DispatchApi.checkDeparture({
      planId: checkForm.value.planId,
      vehicleId: checkForm.value.vehicleId!,
      pass: checkForm.value.pass,
      remark: checkForm.value.remark,
    })
    message.success('发车核验已提交')
    checkVisible.value = false
    getPlanList()
  } finally {
    checkLoading.value = false
  }
}

/** 方案详情 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<DispatchApi.DispatchPlanRespVO>()
// 经停序列按 visitSequence 排序
const detailItems = computed(() =>
  [...(detail.value?.items ?? [])].sort((a, b) => (a.visitSequence ?? 0) - (b.visitSequence ?? 0))
)
const openDetail = async (row: DispatchApi.DispatchPlanVO) => {
  detail.value = undefined
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await DispatchApi.getDispatchPlan(row.id!)
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  getPoolList()
  getPlanList()
  loadSimpleLists()
})
</script>
