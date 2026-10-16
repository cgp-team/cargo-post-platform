<template>
  <ContentWrap title="订单管理">
    <div>
      <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
        <el-form-item label="订单号">
          <el-input v-model="queryParams.orderNo" placeholder="请输入订单号" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item label="订单类型">
          <el-select v-model="queryParams.orderType" placeholder="请选择" clearable style="width:140px">
            <el-option label="客运" :value="1" />
            <el-option label="货运" :value="2" />
            <el-option label="邮快件" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
          <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="mt-15px">
      <el-button type="primary" v-hasPermi="['transport:order:create']" @click="openForm('create')">
        <Icon icon="ep:plus" />新增订单
      </el-button>
      <el-alert
          v-if="listError"
          type="error"
          :closable="false"
          show-icon
          :title="listError"
          description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
      />
      <el-table v-loading="loading" :data="list" stripe border style="margin-top:16px">
        <el-table-column label="订单号" prop="orderNo" align="center" width="200" />
        <el-table-column label="订单类型" prop="orderType" align="center" width="80">
          <template #default="scope">
            <el-tag :type="orderTypeTag(scope.row.orderType)" size="small">
              {{ orderTypeLabel(scope.row.orderType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订单状态" align="center" width="90">
          <template #default="scope">
            <el-tag :type="orderStatusTag(scope.row.status)" size="small">
              {{ orderStatusLabel(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="取货站点" align="center" min-width="130">
          <template #default="scope">
            {{ scope.row.pickupStationName || stationName(scope.row.pickupStationId) || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="送达站点" align="center" min-width="130">
          <template #default="scope">
            {{ scope.row.deliveryStationName || stationName(scope.row.deliveryStationId) || '-' }}
          </template>
        </el-table-column>
        <!-- 用户原始寄货位置 + 取货方式：校园/IP 定位与车辆可达性不一致时，后台能看出"人在哪、车去哪接" -->
        <el-table-column label="用户寄货位置" align="center" min-width="180">
          <template #default="scope">
            <div v-if="scope.row.originalAddress">{{ scope.row.originalAddress }}</div>
            <div v-else class="text-gray-500">-</div>
            <el-tag v-if="scope.row.pickupServiceMode" size="small" :type="serviceModeTag(scope.row.pickupServiceMode)">
              {{ serviceModeLabel(scope.row.pickupServiceMode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="交接服务站" align="center" min-width="140">
          <template #default="scope">
            {{ scope.row.servicePointStationName || (scope.row.servicePointStationId ? stationName(scope.row.servicePointStationId) : '-') }}
          </template>
        </el-table-column>
        <el-table-column label="物品信息" align="left" min-width="200">
          <template #default="scope">
            <el-tooltip :content="scope.row.goodsName || '-'" placement="top" :disabled="!(scope.row.goodsName && scope.row.goodsName.length > 12)">
              <div class="truncate max-w-190px">{{ scope.row.goodsName || '-' }}</div>
            </el-tooltip>
            <div class="text-gray-500 text-xs">
              {{ scope.row.cargoCategory || '未分类' }}
              · {{ scope.row.cargoItemCount != null ? scope.row.cargoItemCount + ' 件' : '-' }}
              · {{ scope.row.cargoWeightKg != null ? scope.row.cargoWeightKg + ' kg' : '-' }}
              · {{ scope.row.cargoVolumeM3 != null ? scope.row.cargoVolumeM3 + ' m³' : '-' }}
              <el-tag v-if="scope.row.freshFlag" size="small" type="danger" style="margin-left:4px">生鲜</el-tag>
            </div>
          </template>
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
        <el-table-column label="操作" align="center" width="260" fixed="right">
          <template #default="scope">
            <el-tooltip content="仅货运待审核订单可审核" placement="top" :disabled="scope.row.orderType === 2 && scope.row.auditStatus === 0">
              <el-button :disabled="!(scope.row.orderType === 2 && scope.row.auditStatus === 0)" link type="warning" v-hasPermi="['transport:order:update']" @click="openAudit(scope.row)">审核</el-button>
            </el-tooltip>
            <el-button link type="primary" @click="openDetail(scope.row)">详情</el-button>
            <el-button link type="primary" v-hasPermi="['transport:order:update']" @click="openForm('update', scope.row.id)">编辑</el-button>
            <el-button link type="danger" v-hasPermi="['transport:order:delete']" @click="handleDelete(scope.row.id)">删除</el-button>
          </template>
        </el-table-column>
      
        <template #empty>
          <el-empty :image-size="60" description="暂无订单，点击下方按钮创建第一笔订单">
            <el-button type="primary" @click="openForm('create')">新增订单</el-button>
          </el-empty>
        </template>
        </el-table>
      <Pagination :total="total" v-model:page="queryParams.pageNo" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>
  </ContentWrap>
  <OrderForm ref="formRef" @success="getList" />

  <!-- 货运物品审核 -->
  <Dialog v-model="auditVisible" title="审核货运物品" width="480px">
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
      <el-form-item label="物品规格">
        <span>
          {{ auditRow.cargoCategory || '未分类' }} ·
          {{ auditRow.cargoItemCount != null ? auditRow.cargoItemCount + ' 件' : '-' }} ·
          {{ auditRow.cargoVolumeM3 != null ? auditRow.cargoVolumeM3 + ' m³' : '-' }}
          {{ auditRow.freshFlag ? ' · 生鲜需冷链' : '' }}
        </span>
      </el-form-item>
      <el-form-item label="取货方式">
        <span>
          {{ serviceModeLabel(auditRow.pickupServiceMode) }}
          <template v-if="auditRow.originalAddress"> · 用户位置：{{ auditRow.originalAddress }}</template>
        </span>
      </el-form-item>
      <el-form-item label="交接服务站">
        <span>{{ auditRow.servicePointStationName || (auditRow.pickupStationName) || '-' }}</span>
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

  <!-- 订单详情：寄货全链路（用户位置 → 交接服务站 → 货物规格 → 收件信息 → 图片凭证） -->
  <Dialog v-model="detailVisible" :title="`订单详情 ${detail?.orderNo || ''}`" width="720px" v-loading="detailLoading">
    <el-alert
      v-if="detailError"
      type="warning"
      :closable="false"
      show-icon
      :title="detailError"
      style="margin-bottom:12px"
    />
    <el-descriptions v-if="detail" :column="2" border size="small">
      <el-descriptions-item label="订单类型">{{ orderTypeLabel(detail.orderType!) }}</el-descriptions-item>
      <el-descriptions-item label="订单状态">
        <el-tag :type="orderStatusTag(detail.status)" size="small">{{ orderStatusLabel(detail.status) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="取货站点">{{ detail.pickupStationName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="送达站点">{{ detail.deliveryStationName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="用户寄货位置">
        {{ detail.originalAddress || '-' }}
        <span v-if="detail.originalLatitude && detail.originalLongitude" class="text-gray-500">
          （{{ Number(detail.originalLongitude).toFixed(5) }}, {{ Number(detail.originalLatitude).toFixed(5) }}）
        </span>
      </el-descriptions-item>
      <el-descriptions-item label="取货方式">{{ serviceModeLabel(detail.pickupServiceMode) }}</el-descriptions-item>
      <el-descriptions-item label="交接服务站">
        {{ detail.servicePointStationName || '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="承运审核">
        {{ reviewStatusLabel(detail.reviewStatus) }}
        <span v-if="detail.reviewReasonCodes" class="text-gray-500">（{{ detail.reviewReasonCodes }}）</span>
      </el-descriptions-item>
      <el-descriptions-item label="货物名称">{{ detail.goodsName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="货物类别">{{ detail.cargoCategory || '-' }}</el-descriptions-item>
      <el-descriptions-item label="件数">{{ detail.cargoItemCount != null ? detail.cargoItemCount + ' 件' : '-' }}</el-descriptions-item>
      <el-descriptions-item label="重量">{{ detail.cargoWeightKg != null ? detail.cargoWeightKg + ' kg' : '-' }}</el-descriptions-item>
      <el-descriptions-item label="体积">{{ detail.cargoVolumeM3 != null ? detail.cargoVolumeM3 + ' m³' : '-' }}</el-descriptions-item>
      <el-descriptions-item label="是否生鲜">{{ detail.freshFlag ? '是（需冷链）' : '否' }}</el-descriptions-item>
      <el-descriptions-item label="收件人">{{ detail.receiverName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="收件电话">{{ detail.receiverMobile || '-' }}</el-descriptions-item>
      <el-descriptions-item label="收件地址" :span="2">{{ detail.receiverAddress || '-' }}</el-descriptions-item>
      <el-descriptions-item label="备注" :span="2">{{ detail.goodsNote || '无' }}</el-descriptions-item>
      <el-descriptions-item label="寄件照" :span="2">
        <el-image
          v-if="detail.photoUrl"
          :src="detail.photoUrl"
          :preview-src-list="[detail.photoUrl]"
          fit="cover"
          style="width:120px;height:120px;border-radius:8px"
        />
        <span v-else class="text-gray-500">无</span>
      </el-descriptions-item>
      <el-descriptions-item label="司机收件照" :span="2">
        <el-image
          v-if="detail.driverPhotoUrl"
          :src="detail.driverPhotoUrl"
          :preview-src-list="[detail.driverPhotoUrl]"
          fit="cover"
          style="width:120px;height:120px;border-radius:8px"
        />
        <span v-else class="text-gray-500">无</span>
      </el-descriptions-item>
    </el-descriptions>

    <!-- 事件时间线：读 /transport/order-event/list-by-order（权限复用 transport:order:query）。
         ⚠ 三格必须分开念：读到 N 条 / 后端确实说没有 / 这一格没读到（失败原因照实显示）。 -->
    <div class="mt-16px" v-loading="eventsLoading">
      <div class="mb-8px font-bold">事件时间线</div>
      <el-alert v-if="eventsError" type="warning" :closable="false" show-icon
                :title="`事件流没读到：${eventsError}`"
                description="这不代表该订单没有事件；下面的订单状态仍是后端回的状态。" />
      <el-timeline v-else-if="events.length">
        <el-timeline-item
          v-for="ev in events"
          :key="ev.id"
          :timestamp="eventTimeText(ev.eventTime)"
          placement="top">
          <span class="font-bold">{{ ev.eventTypeName || ev.eventType || '后端没给类型名' }}</span>
          <span v-if="ev.operator" class="text-gray-500"> · {{ ev.operator }}</span>
          <div v-if="ev.detail" class="text-gray-600">{{ ev.detail }}</div>
        </el-timeline-item>
      </el-timeline>
      <!-- 还在飞的时候不许念"后端说没有"：那等于替后端宣布了一个它还没给的结论 -->
      <span v-else-if="!eventsLoading" class="text-gray-500">后端说这单没有事件记录（接口正常返回空列表，不是读取失败）</span>
    </div>
    <template #footer>
      <el-button @click="detailVisible = false">关 闭</el-button>
    </template>
  </Dialog>
</template>

<script setup lang="ts">
import * as OrderApi from '@/api/transport/order'
import * as OrderEventApi from '@/api/transport/orderEvent'
import * as StationApi from '@/api/transport/station'
import dayjs from 'dayjs'
import {
  orderTypeLabel,
  orderTypeTag,
  orderStatusLabel,
  orderStatusTag,
  serviceModeLabel,
  serviceModeTag,
  reviewStatusLabel
} from '../constants'
import OrderForm from './OrderForm.vue'
import { useStationDictStore } from '@/store/modules/stationDict'

defineOptions({ name: 'TransportOrder' })

const message = useMessage()
const stationDict = useStationDictStore()
const loading = ref(true)
const listError = ref('')
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


/** 站点名兜底（后端已返回 pickupStationName 时不再依赖） */
const stations = ref<StationApi.StationVO[]>([])
const stationName = (id?: number) => (id == null ? '' : stations.value.find((s) => s.id === id)?.stationName || '')
const loadStations = async () => {
  try {
    stations.value = await stationDict.load()
  } catch (e) {
    // WEB-10: 兜底数据失败也要可见提示（不影响订单列表主流程）
    message.error('站点信息加载失败，下单地址可能不完整')
  }
}

const getList = async () => {
  loading.value = true
  try {
    const res = await OrderApi.getOrderPage(queryParams)
    list.value = res.list
    total.value = res.total
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    // （队列 #68 / 台账 D-134；判据 scripts/check_admin_catch_leaves_data.py）
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有数据」）')
  } finally { loading.value = false }
}
const handleQuery = () => { queryParams.pageNo = 1; getList() }  // 搜索必须先回第 1 页：带着旧页码查新条件，新条件下常常没有第 N 页 ⇒ 表格空、念成"暂无数据"
const resetQuery = () => { Object.assign(queryParams, { pageNo: 1, pageSize: 10, orderNo: '', orderType: undefined }); getList() }
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try {
    await message.confirm('确认删除该订单？')
    await OrderApi.deleteOrder(id)
    message.success('删除成功')
    // WEB-26: 当前页删空后回退一页，避免停在空页
    if (list.value.length === 1 && queryParams.pageNo > 1) queryParams.pageNo -= 1
    getList()
  } catch (e) { /* cancelled */ }
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
  // 二次确认：拒绝承运会直接把订单置为已取消（用户端立即可见），且没有撤销入口
  if (!pass) {
    const no = auditRow.value.orderNo || auditRow.value.id
    try {
      await message.confirm(`确认拒绝订单「${no}」？拒绝后订单将取消，不可恢复。`)
    } catch (e) {
      return
    }
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

/** 订单详情：单独拉一条（含子表全字段），复核后可直接给老师看"用户位置 → 交接站" */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<OrderApi.OrderVO>({})
/** 事件流三格：events（读到的）/ eventsError（没读到的原因）/ 两者都空 = 后端确实说没有 */
const events = ref<OrderEventApi.OrderEventVO[]>([])
const eventsError = ref('')
const eventsLoading = ref(false)
const eventTimeText = (v?: number) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '后端没给时间')

const loadEvents = async (orderId: number) => {
  events.value = []
  eventsError.value = ''
  eventsLoading.value = true
  try {
    events.value = (await OrderEventApi.getOrderEvents(orderId)) || []
  } catch (e: any) {
    // 失败不许塌成"没有事件"：那一格念错，运营就会以为这单没动过
    eventsError.value = e?.msg || e?.message || '未知原因（后端没给出原因）'
  } finally {
    eventsLoading.value = false
  }
}

const openDetail = async (row: OrderApi.OrderVO) => {
  detail.value = row // 先用列表数据渲染，避免白屏
  detailError.value = ''
  detailVisible.value = true
  detailLoading.value = true
  loadEvents(row.id!)
  try {
    detail.value = await OrderApi.getOrder(row.id!)
  } catch (e: any) {
    // 这一格特殊：失败时屏幕上**不是空白**，而是列表那一行 —— 那也是数据，但不是后端刚给的那份。
    // 不点名就会被读成"详情就是这样"（队列 #68 / 台账 D-134）
    detailError.value =
      '详情没读到：' + (e?.msg || e?.message || '未知原因') + '（下面念的是列表里那一行的字段，不是后端此刻的状态）'
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  getList()
  loadStations()
})
</script>
