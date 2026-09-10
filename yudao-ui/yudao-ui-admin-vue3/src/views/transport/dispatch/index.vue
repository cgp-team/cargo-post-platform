<template>
  <ContentWrap title="调度工作台">
    <!-- 订单池 -->
    <ContentWrap title="订单池">
      <el-form :inline="true" :model="poolQuery" @submit.prevent="getPoolList">
        <el-form-item label="订单状态">
          <el-select v-model="poolQuery.status" placeholder="请选择" clearable style="width:140px">
            <el-option label="待入池" :value="8" />
            <el-option label="已入池" :value="1" />
            <el-option label="已分配" :value="2" />
            <el-option label="已发车" :value="3" />
            <el-option label="已完成" :value="4" />
            <el-option label="已取消" :value="5" />
            <el-option label="待客户操作" :value="7" />
            <el-option label="待审核" :value="6" />
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
      <el-button type="primary" v-hasPermi="['transport:dispatch:collect']" :disabled="collectDisabled" @click="submitCollectBySelection">
        <Icon icon="ep:download" />归集入池
      </el-button>
      <el-button type="primary" v-hasPermi="['transport:dispatch:manual-plan']" :disabled="manualSelected.length === 0" @click="openManual">
        <Icon icon="ep:pointer" />手工派单
      </el-button>
      <el-button type="primary" v-hasPermi="['transport:dispatch:smart-plan']" @click="openSmart"><Icon icon="ep:magic-stick" />智能派单</el-button>
      <el-button
        type="success"
        v-hasPermi="['transport:dispatch:smart-plan']"
        :loading="demoRunning"
        @click="runOneClickDemo"
      >
        <Icon icon="ep:video-play" />一键演示（归集→调度→审核→核验）
      </el-button>
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

  <!-- 手工派单弹窗 -->
  <Dialog title="手工派单" v-model="manualVisible" width="500px">
    <el-form ref="manualFormRef" :model="manualForm" :rules="manualRules" label-width="120px" v-loading="manualLoading">
      <el-form-item label="已选订单">
        <span>{{ manualSelected.length }} 条</span>
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

  <!-- 智能调度弹窗：默认一键（后端自动选场站/车辆/参数），高级设置里才手动指定 -->
  <Dialog title="智能调度" v-model="smartVisible" width="720px">
    <el-steps :active="smartStep" finish-status="success" align-center style="margin-bottom:16px">
      <el-step title="智能调度" />
      <el-step title="高级设置" />
    </el-steps>

    <!-- 第 1 步：约束校验 / 运力预警 -->
    <div v-show="smartStep === 0" v-loading="smartLoading">
      <!-- 一键智能调度：不选场站、不选车辆、不填参数 -->
      <el-descriptions :column="2" border size="small" style="margin-bottom:14px">
        <el-descriptions-item label="待调度订单">{{ poolCount }} 单</el-descriptions-item>
        <el-descriptions-item label="当前可用车辆">{{ vehicleList.length }} 台</el-descriptions-item>
        <el-descriptions-item label="候选场站">自动（按订单分布推导）</el-descriptions-item>
        <el-descriptions-item label="算法">HACO-CPS v1.4.1</el-descriptions-item>
      </el-descriptions>

      <el-steps v-if="autoRunning" :active="autoProgress" finish-status="process" align-center style="margin-bottom:12px">
        <el-step v-for="stage in autoStages" :key="stage" :title="stage" />
      </el-steps>

      <el-alert
        v-if="autoResult"
        type="success"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        :title="`智能调度完成：方案 #${autoResult.id}`"
        :description="`订单 ${autoResult.orderCount ?? '-'} 单 · 车辆 ${autoResult.vehicleCount ?? '-'} 台 · 场站 ${autoResult.depotStationName || '自动选择'} · 总里程 ${autoResult.totalDistance ?? '-'} km · 算法 ${autoResult.algorithmVersion || 'HACO-CPS v1.4.1'}`"
      />

      <el-button type="primary" size="large" :loading="smartLoading" @click="runAutoSmart">
        <Icon icon="ep:magic-stick" /> {{ autoResult ? '重新智能调度' : '开始智能调度' }}
      </el-button>
      <el-button v-if="autoResult?.id" @click="viewPlan(autoResult!.id!)">查看方案</el-button>

      <el-divider content-position="left">高级设置（可选）</el-divider>
      <el-collapse v-model="smartAdvanced">
        <el-collapse-item name="advanced" title="手动指定场站 / 车辆 / 算法参数">
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
        </el-collapse-item>
      </el-collapse>

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
// 一键演示需要"确认框 + 步骤 loading 文案"：按项目约定显式引入（不做全局挂载）
import { ElLoading, ElMessageBox } from 'element-plus'

defineOptions({ name: 'TransportDispatch' })

const message = useMessage()

type TagType = 'primary' | 'success' | 'warning' | 'danger' | 'info'

// 订单类型:1 客运 2 货运 3 邮快件
const orderTypeLabelMap: Record<number, string> = { 1: '客运', 2: '货运', 3: '邮快件' }
const orderTypeLabel = (type?: number) => (type === undefined ? '-' : orderTypeLabelMap[type] || '未知')
const orderTypeTagMap: Record<number, TagType> = { 1: 'success', 2: 'warning', 3: 'info' }
const orderTypeTag = (type?: number): TagType => (type === undefined ? 'info' : orderTypeTagMap[type] || 'info')

// 订单状态(OrderLifecycle):0 已创建 6 待审核 7 待客户操作 8 待入池 1 已入池 2 已分配 3 已发车 4 已完成 5 已取消
// Phase 2/3：承运审核前置——仅「待入池(8)」可归集入池
const orderStatusLabelMap: Record<number, string> = { 0: '已创建', 6: '待审核', 7: '待客户操作', 8: '待入池', 1: '已入池', 2: '已分配', 3: '已发车', 4: '已完成', 5: '已取消' }
const orderStatusLabel = (status?: number) => (status === undefined ? '-' : orderStatusLabelMap[status] || '未知')
const orderStatusTagMap: Record<number, TagType> = { 0: 'info', 6: 'warning', 7: 'warning', 8: 'warning', 1: 'warning', 2: 'primary', 3: 'success', 4: 'success', 5: 'danger' }
const orderStatusTag = (status?: number): TagType => (status === undefined ? 'info' : orderStatusTagMap[status] || 'info')

// 方案状态:0 待审核 1 已下发 2 执行中 3 已完成 4 已作废
const planStatusLabelMap: Record<number, string> = { 0: '待审核', 1: '已下发', 2: '执行中', 3: '已完成', 4: '已作废' }
const planStatusLabel = (status?: number) => (status === undefined ? '-' : planStatusLabelMap[status] || '未知')
const planStatusTagMap: Record<number, TagType> = { 0: 'warning', 1: 'primary', 2: 'success', 3: 'info', 4: 'danger' }
const planStatusTag = (status?: number): TagType => (status === undefined ? 'info' : planStatusTagMap[status] || 'info')

// 派单方式:0 手工 1 智能
const modeLabel = (mode?: number) => (mode === undefined ? '-' : mode === 1 ? '智能' : '手工')
const modeTag = (mode?: number): TagType => (mode === 1 ? 'success' : 'info')

// 经停动作:0 出发 1 接客 2 送客 3 派送 4 揽收 5 返回 6 经停
const actionLabelMap: Record<number, string> = { 0: '出发', 1: '接客', 2: '送客', 3: '派送', 4: '揽收', 5: '返回', 6: '经停' }
const actionLabel = (type?: number) => (type === undefined ? '-' : actionLabelMap[type] || '未知')
const actionTagMap: Record<number, TagType> = { 0: 'info', 1: 'success', 2: 'warning', 3: 'primary', 4: 'primary', 5: 'info', 6: 'info' }
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
// 待入池(status=8)可勾选归集入池；已入池(status=1)可勾选手工/智能派单
const poolSelectable = (row: DispatchApi.DispatchOrderVO) => row.status === 8 || row.status === 1
const handleSelectionChange = (rows: DispatchApi.DispatchOrderVO[]) => {
  selectedOrders.value = rows
}
/** 可手工派单订单（已入池 status=1） */
const manualSelected = computed(() => selectedOrders.value.filter((o) => o.status === 1))

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

/** 归集入池：勾选待入池订单后按 orderIds 归集（仅承运审核通过的待入池订单可入池） */
const collectLoading = ref(false)
/** 可归集订单（待入池 status=8） */
const collectableSelected = computed(() => selectedOrders.value.filter((o) => o.status === 8))
/** 归集按钮禁用：当前筛选已入池(status=1) 或 无待入池勾选 */
const collectDisabled = computed(() => poolQuery.status === 1 || collectableSelected.value.length === 0)
const submitCollectBySelection = async () => {
  if (!collectableSelected.value.length) {
    message.warning('请先选择待入池的订单')
    return
  }
  collectLoading.value = true
  try {
    const count = await DispatchApi.collectOrders({ orderIds: collectableSelected.value.map((o) => o.id!) })
    message.success(`归集完成,共入池 ${count} 条订单`)
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
  if (!manualSelected.value.length) {
    message.warning('请选择已入池的订单')
    return
  }
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
      orderIds: manualSelected.value.map((o) => o.id!),
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
/** 一键智能调度：进度阶段 / 结果摘要（默认路径，管理员无需任何选择） */
const smartAdvanced = ref<string[]>([])
const autoRunning = ref(false)
const autoProgress = ref(0)
const autoStages = ['分析订单与约束', '检查车辆运力', '选择调度场站', '运行 HACO-CPS', '生成调度方案']
const autoResult = ref<DispatchApi.DispatchPlanRespVO>()
/** 订单池中"待调度"（已入池 status=1）数量，供一键弹窗展示 */
const poolCount = computed(() => poolList.value.filter((o) => o.status === 1).length || poolTotal.value)
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

/**
 * 一键智能调度：后端自动选场站 + 自动挑候选车辆 + 算法默认参数，前端只点一次。
 * 进度条按阶段演示（真实耗时为算法调用），完成后展示方案摘要并支持"查看方案"。
 */
const runAutoSmart = async () => {
  smartLoading.value = true
  autoRunning.value = true
  autoProgress.value = 0
  autoResult.value = undefined
  const timer = window.setInterval(() => {
    if (autoProgress.value < autoStages.length - 1) autoProgress.value += 1
  }, 900)
  try {
    // 1) 约束校验（auto=true：后端自动推导场站与候选车辆）
    const validated = await DispatchApi.validateDispatch({ auto: true })
    validateResult.value = validated
    if (validated.capacityCheck?.overCapacity) {
      autoProgress.value = 1
      message.warning('运力不足：算法会自动增加车辆或给出不可行原因，可继续提交')
    }
    // 2) 智能调度（算法决定实际使用几辆车）
    const planId = await DispatchApi.createSmartPlan({ auto: true })
    // 3) 读取方案摘要（订单数/车辆数/里程/算法版本）
    const plan = await DispatchApi.getDispatchPlan(planId)
    autoResult.value = plan
    autoProgress.value = autoStages.length
    message.success(`智能调度完成，方案号：${planId}`)
    getPlanList()
  } finally {
    window.clearInterval(timer)
    autoRunning.value = false
    smartLoading.value = false
  }
}

/** 查看方案：跳到方案列表并高亮（方案详情由列表行内查看） */
const viewPlan = (planId: number) => {
  smartVisible.value = false
  message.info(`方案 #${planId} 已生成，可在下方「调度方案」列表查看详情`)
  getPlanList()
}

/**
 * 一键演示：归集全部待入池 → 一键智能调度 → 自动审核通过 → 自动发车核验。
 * 现场演示只点一次，随后即可去小程序端看"我的寄货提醒 / 实时公交 / 司机端任务"。
 * 每一步都复用正式接口与权限校验，不是特制后门。
 */
const demoRunning = ref(false)
const runOneClickDemo = async () => {
  try {
    await ElMessageBox.confirm(
      '将依次执行：① 归集全部「待入池」订单 ② 一键智能调度（自动选场站/车辆） ③ 方案审核通过 ④ 发车核验。是否继续？',
      '一键演示',
      { type: 'warning', confirmButtonText: '开始演示', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  demoRunning.value = true
  const loading = ElLoading.service({ text: '① 归集订单入池…', background: 'rgba(0,0,0,0.15)' })
  try {
    // ① 归集全部待入池订单
    const collected = await DispatchApi.collectOrders({ all: true })
    loading.setText(`① 已归集 ${collected} 单，② 正在智能调度…`)
    getPoolList()
    // ② 一键智能调度（后端自动选场站与候选车辆，算法决定实际车辆数）
    const planId = await DispatchApi.createSmartPlan({ auto: true })
    loading.setText('③ 正在审核方案…')
    // ③ 方案审核通过
    await DispatchApi.reviewDispatchPlan({ planId, approve: true, reason: '一键演示自动审核通过' })
    // ④ 逐车发车核验通过
    const plan = await DispatchApi.getDispatchPlan(planId)
    const vehicleIds = Array.from(
      new Set((plan.items || []).map((i) => i.vehicleId).filter((v): v is number => !!v))
    )
    for (const vehicleId of vehicleIds) {
      loading.setText(`④ 正在发车核验（车辆 ${vehicleId}）…`)
      await DispatchApi.checkDeparture({ planId, vehicleId, pass: true })
    }
    loading.close()
    await ElMessageBox.alert(
      `归集订单：${collected} 单\n方案：#${planId}（订单 ${plan.orderCount ?? '-'} 单 / 车辆 ${plan.vehicleCount ?? vehicleIds.length} 台 / 场站 ${plan.depotStationName || '自动选择'}）\n\n接下来请到小程序端演示：司机端「工作台 → 发车」、用户端「快递页看车快到了提醒 / 实时公交」。`,
      '一键演示完成',
      { type: 'success', confirmButtonText: '知道了' }
    )
    getPlanList()
  } catch (e) {
    loading.close()
    // 业务错误已由 axios 统一提示；这里补充语义化说明，便于现场判断卡在哪一步
    message.error('一键演示中断：请确认存在「待入池」订单且后台已配置可用车辆（详见列表与上一条错误提示）')
  } finally {
    demoRunning.value = false
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
