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
        <el-table-column label="总里程(km)" prop="totalDistance" align="center">
          <template #default="scope">{{ totalDistanceText(scope.row.totalDistance) }}</template>
        </el-table-column>
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

  <!-- 智能派单弹窗（两步：约束校验 → 算法参数） -->
  <Dialog title="智能派单" v-model="smartVisible" width="720px">
    <el-steps :active="smartStep" finish-status="success" align-center style="margin-bottom:16px">
      <el-step title="约束校验" />
      <el-step title="算法参数" />
    </el-steps>

    <!-- 第 1 步：约束校验 / 运力预警 -->
    <div v-show="smartStep === 0" v-loading="smartLoading">
      <el-form :model="smartForm" label-width="100px">
        <el-form-item label="场站">
          <el-select v-model="smartForm.depotStationId" placeholder="请选择场站" style="width:100%" @change="validateResult = undefined">
            <el-option v-for="s in stationList" :key="s.id!" :label="s.stationName" :value="s.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="可用车辆">
          <el-select v-model="smartForm.vehicleIds" placeholder="请选择车辆(最多 3 台)" multiple :multiple-limit="3" style="width:100%" @change="validateResult = undefined">
            <el-option v-for="v in vehicleList" :key="v.id!" :label="v.plateNo" :value="v.id!" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="smartLoading"
            :disabled="!smartForm.depotStationId || smartForm.vehicleIds.length === 0"
            @click="runValidate"
          >
            开始校验
          </el-button>
        </el-form-item>
      </el-form>

      <template v-if="validateResult">
        <!-- 运力不足预警 -->
        <el-alert
          v-if="validateResult.capacityCheck?.overCapacity"
          type="error"
          :closable="false"
          show-icon
          title="运力不足预警"
          :description="`本时段乘客 ${validateResult.orderStats?.passengerCount ?? 0} 人(上限 ${validateResult.capacityCheck?.totalPassengerCapacity})、包裹 ${validateResult.orderStats?.parcelCount ?? 0} 件(上限 ${validateResult.capacityCheck?.totalCargoCapacity})，单车单趟无法一次性完成全部业务`"
          style="margin-bottom:12px"
        />
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          title="总容量充足"
          :description="`乘客 ${validateResult.orderStats?.passengerCount ?? 0} 人 / 包裹 ${validateResult.orderStats?.parcelCount ?? 0} 件，总量在车辆总容量内；单车容量与时序仍由算法最终校验`"
          style="margin-bottom:12px"
        />

        <!-- 订单统计三栏 -->
        <div class="validate-stats">
          <div class="stat-item">
            <div class="stat-num">{{ validateResult.orderStats?.passengerCount ?? 0 }}</div>
            <div class="stat-label">乘车需求(人)</div>
          </div>
          <div class="stat-item">
            <div class="stat-num">{{ validateResult.orderStats?.pickupCount ?? 0 }}</div>
            <div class="stat-label">揽收(件)</div>
          </div>
          <div class="stat-item">
            <div class="stat-num">{{ validateResult.orderStats?.deliveryCount ?? 0 }}</div>
            <div class="stat-label">派送(件)</div>
          </div>
        </div>

        <!-- 车辆容量 -->
        <el-table
          v-if="validateResult.vehicles?.length"
          :data="validateResult.vehicles"
          size="small"
          border
          style="margin-top:12px"
        >
          <el-table-column label="车牌" prop="plateNo" align="center" />
          <el-table-column label="载客上限" prop="passengerCapacity" align="center" />
          <el-table-column label="载货上限" prop="cargoCapacity" align="center" />
        </el-table>

        <!-- 站点作业标记（上车绿点/下车红点/派送/揽收） -->
        <div v-if="validateResult.markers?.length" class="marker-list">
          <div class="marker-item" v-for="m in validateResult.markers" :key="m.stationId">
            <span class="marker-dots">
              <i v-for="t in m.types" :key="t" :class="markerDotClass(t)">{{ markerDotText(t) }}</i>
            </span>
            <span class="marker-name">{{ m.stationName ?? m.stationId }} · {{ m.orderCount }}单</span>
          </div>
        </div>

        <!-- 客运时序问题 -->
        <el-alert
          v-if="validateResult.timeSeqIssues?.length"
          type="warning"
          :closable="false"
          show-icon
          title="客运时序问题"
          :description="validateResult.timeSeqIssues!.map((i) => `${i.orderNo}:${i.issue}`).join('；')"
          style="margin-top:12px"
        />

        <div style="margin-top:16px; text-align:right">
          <el-button v-if="validateResult.capacityCheck?.overCapacity" type="primary" disabled>
            运力不足，请增派车辆或调整订单
          </el-button>
          <el-button v-else type="primary" @click="smartStep = 1">运力充足，下一步</el-button>
        </div>
      </template>
    </div>

    <!-- 第 2 步：算法参数 -->
    <div v-show="smartStep === 1">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="蚁群算法(ACO)参数"
        description="全部可选，不填使用算法默认值(ant_count=30, max_iterations=100, alpha=1.0, beta=3.0, rho=0.1, Q=100, convergence_threshold=20)"
        style="margin-bottom:12px"
      />
      <el-form :model="acoForm" label-width="150px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="蚂蚁种群数量"><el-input-number v-model="acoForm.ant_count" :min="1" :max="100" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大迭代次数"><el-input-number v-model="acoForm.max_iterations" :min="1" :max="500" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="信息素因子 α"><el-input-number v-model="acoForm.alpha" :min="0" :max="5" :step="0.1" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="启发因子 β"><el-input-number v-model="acoForm.beta" :min="0" :max="10" :step="0.1" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="挥发系数 ρ"><el-input-number v-model="acoForm.rho" :min="0" :max="0.5" :step="0.01" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="信息素增量 Q"><el-input-number v-model="acoForm.Q" :min="1" :max="1000" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="收敛判定阈值"><el-input-number v-model="acoForm.convergence_threshold" :min="1" :max="100" controls-position="right" style="width:100%" /></el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="smartVisible = false">取 消</el-button>
      <el-button v-if="smartStep === 1" @click="smartStep = 0">上一步</el-button>
      <el-button v-if="smartStep === 1" type="primary" :loading="smartLoading" @click="submitSmart">提交规划</el-button>
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
    <el-descriptions v-if="detail" :column="4" border size="small" style="margin-bottom:12px">
      <el-descriptions-item label="总里程">{{ totalDistanceText(detail.totalDistance) }} km</el-descriptions-item>
      <el-descriptions-item label="预计耗时">
        {{ detail.estDurationMinutes != null ? detail.estDurationMinutes + ' 分钟' : '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="预计收入">
        {{ detail.estRevenue != null ? Number(detail.estRevenue).toFixed(2) + ' 元' : '-' }}
      </el-descriptions-item>
      <el-descriptions-item label="预计成本">
        {{ detail.estCost != null ? Number(detail.estCost).toFixed(2) + ' 元' : '-' }}
      </el-descriptions-item>
    </el-descriptions>
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
/** 总里程展示：后端已按经停坐标换算为公里，保留 1 位小数 */
const totalDistanceText = (v?: number) => (v == null ? '-' : Number(v).toFixed(1))
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
/** 'YYYY-MM-DD HH:mm:ss' → 毫秒时间戳（后端 LocalDateTime 全局按时间戳序列化，@RequestBody 不认空格日期字符串） */
const toTimestamp = (s: string): number | undefined =>
  s ? new Date(s.replace(' ', 'T')).getTime() : undefined
const submitCollect = async () => {
  const valid = await collectFormRef.value?.validate()
  if (!valid) return
  // 运行时校验并收窄类型（collectOrders 期望 number，表单必填保证正常情况非空）
  const startTs = toTimestamp(collectForm.value.batchStart)
  const endTs = toTimestamp(collectForm.value.batchEnd)
  if (startTs == null || endTs == null) {
    message.error('请选择批次开始/结束时间')
    return
  }
  collectLoading.value = true
  try {
    const count = await DispatchApi.collectOrders({ batchStart: startTs, batchEnd: endTs })
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

/** 智能派单（两步：约束校验 → 算法参数） */
const smartVisible = ref(false)
const smartLoading = ref(false)
const smartStep = ref(0)
const smartForm = ref<{ depotStationId?: number; vehicleIds: number[] }>({
  depotStationId: undefined,
  vehicleIds: [],
})
const validateResult = ref<DispatchApi.DispatchValidateRespVO>()
const acoForm = reactive<Record<string, number | undefined>>({
  ant_count: undefined,
  max_iterations: undefined,
  alpha: undefined,
  beta: undefined,
  rho: undefined,
  Q: undefined,
  convergence_threshold: undefined,
})
const openSmart = () => {
  smartForm.value = { depotStationId: undefined, vehicleIds: [] }
  validateResult.value = undefined
  smartStep.value = 0
  Object.keys(acoForm).forEach((k) => (acoForm[k] = undefined))
  smartVisible.value = true
}
/** 第 1 步：约束校验 / 运力预警 */
const runValidate = async () => {
  if (!smartForm.value.depotStationId || smartForm.value.vehicleIds.length === 0) return
  smartLoading.value = true
  try {
    validateResult.value = await DispatchApi.validateDispatch({
      depotStationId: smartForm.value.depotStationId!,
      vehicleIds: smartForm.value.vehicleIds,
    })
  } finally {
    smartLoading.value = false
  }
}
/** 站点作业标记：动作 → 文案/样式（上车绿点/下车红点/派送揽收金色） */
const markerDotText = (t: string) => (t === 'BOARD' ? '上' : t === 'ALIGHT' ? '下' : t === 'DELIVER' ? '派' : '揽')
const markerDotClass = (t: string) =>
  t === 'BOARD' ? 'dot-green' : t === 'ALIGHT' ? 'dot-red' : 'dot-gold'
/** 第 2 步：提交规划（ACO 参数为空则不传，用算法默认值） */
const submitSmart = async () => {
  smartLoading.value = true
  try {
    const algorithmConfig = Object.entries(acoForm).reduce<Record<string, number>>((acc, [k, v]) => {
      if (v !== undefined && v !== null) acc[k] = v
      return acc
    }, {})
    const planId = await DispatchApi.createSmartPlan({
      depotStationId: smartForm.value.depotStationId!,
      vehicleIds: smartForm.value.vehicleIds,
      algorithmConfig: Object.keys(algorithmConfig).length ? algorithmConfig : undefined,
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

<style lang="scss" scoped>
.validate-stats {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}
.stat-item {
  flex: 1;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 8px;
  text-align: center;
}
.stat-num {
  font-size: 26px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1.2;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.marker-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.marker-item {
  display: inline-flex;
  align-items: center;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 4px 10px;
}
.marker-dots {
  display: inline-flex;
  gap: 4px;
  margin-right: 6px;
}
.marker-dots i {
  font-style: normal;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
}
.marker-dots .dot-green {
  background: var(--el-color-success);
}
.marker-dots .dot-red {
  background: var(--el-color-danger);
}
.marker-dots .dot-gold {
  background: var(--el-color-warning);
}
.marker-name {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
