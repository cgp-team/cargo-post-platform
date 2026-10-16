<template>
  <ContentWrap title="客货空间冲突检视">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="排班保存前的确定性校验"
      description="把「核定载客/载货」与「预报量」两组数放到一起比大小，列出冲突点与缓冲建议。本页只做只读计算，不改任何排班。容量留空表示「未录」，不会当成 0 来判。"
    />
  </ContentWrap>

  <ContentWrap title="车辆核定容量">
    <el-form :inline="true" style="margin-bottom: 8px">
      <el-form-item label="场景标识">
        <el-input v-model="sceneLabel" placeholder="如：早班 06:30 石桥线" clearable style="width: 260px" />
      </el-form-item>
    </el-form>
    <el-table :data="vehicles" border stripe>
      <el-table-column label="车辆标识" min-width="160">
        <template #default="scope">
          <el-input v-model="scope.row.label" placeholder="车牌 / 编号" />
        </template>
      </el-table-column>
      <el-table-column label="核定载客(人)" min-width="140">
        <template #default="scope">
          <el-input-number v-model="scope.row.passengerCapacity" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="核定货仓(件)" min-width="140">
        <template #default="scope">
          <el-input-number v-model="scope.row.cargoCapacityItems" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="核定载重(kg)" min-width="140">
        <template #default="scope">
          <el-input-number v-model="scope.row.cargoCapacityKg" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="scope">
          <el-button link type="danger" @click="removeVehicle(scope.$index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button style="margin-top: 12px" @click="addVehicle"><Icon icon="ep:plus" />添加车辆</el-button>
  </ContentWrap>

  <ContentWrap title="分段预报量">
    <el-table :data="segments" border stripe>
      <el-table-column label="分段名称" min-width="180">
        <template #default="scope">
          <el-input v-model="scope.row.name" placeholder="站点 / 区间" />
        </template>
      </el-table-column>
      <el-table-column label="预报客位(人)" min-width="140">
        <template #default="scope">
          <el-input-number v-model="scope.row.forecastPassenger" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="预报货件(件)" min-width="140">
        <template #default="scope">
          <el-input-number v-model="scope.row.forecastCargoItems" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="预报货重(kg)" min-width="140">
        <template #default="scope">
          <el-input-number v-model="scope.row.forecastCargoKg" :min="0" :controls="false" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="scope">
          <el-button link type="danger" @click="removeSegment(scope.$index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button style="margin-top: 12px" @click="addSegment"><Icon icon="ep:plus" />添加分段</el-button>
  </ContentWrap>

  <ContentWrap>
    <el-button type="primary" :loading="checkLoading" @click="handleCheck"><Icon icon="ep:search" />开始检视</el-button>
    <el-button @click="resetAll">清空</el-button>
    <el-alert
      v-if="errorMsg"
      type="error"
      :closable="false"
      show-icon
      :title="errorMsg"
      description="这张空结果只说明这次检视没成功，不是「没有冲突」。"
    />
  </ContentWrap>

  <ContentWrap v-if="result" title="检视结果">
    <el-alert
      :type="result.overCapacity ? 'error' : 'success'"
      :closable="false"
      show-icon
      :title="result.summary"
    />
    <el-descriptions :column="3" border style="margin-top: 16px">
      <el-descriptions-item label="核定载客合计">{{ result.totalPassengerCapacity }}</el-descriptions-item>
      <el-descriptions-item label="预报客位合计">{{ result.totalPassengerForecast }}</el-descriptions-item>
      <el-descriptions-item label="核定货仓合计(件)">{{ result.totalCargoItemCapacity }}</el-descriptions-item>
      <el-descriptions-item label="预报货件合计(件)">{{ result.totalCargoItemForecast }}</el-descriptions-item>
      <el-descriptions-item label="核定载重合计(kg)">{{ result.totalCargoKgCapacity }}</el-descriptions-item>
      <el-descriptions-item label="预报货重合计(kg)">{{ result.totalCargoKgForecast }}</el-descriptions-item>
    </el-descriptions>
    <el-table :data="result.conflicts" border stripe style="margin-top: 16px">
      <el-table-column label="维度" prop="dimension" align="center" width="130" />
      <el-table-column label="严重度" align="center" width="110">
        <template #default="scope">
          <el-tag :type="scope.row.severity === 'BLOCK' ? 'danger' : 'warning'">{{ scope.row.severity }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="核定" prop="rated" align="center" width="110" />
      <el-table-column label="预报" prop="forecast" align="center" width="110" />
      <el-table-column label="缺口" prop="exceed" align="center" width="110" />
      <el-table-column label="说明" prop="message" min-width="280" show-overflow-tooltip />
      <template #empty>
        <el-empty :image-size="60" description="没有冲突点" />
      </template>
    </el-table>
    <template v-if="result.bufferSuggestions.length">
      <div style="margin-top: 12px; font-weight: 600">缓冲建议</div>
      <ul>
        <li v-for="(s, i) in result.bufferSuggestions" :key="i">{{ s }}</li>
      </ul>
    </template>
    <template v-if="result.unratedNotes.length">
      <div style="margin-top: 12px; font-weight: 600">未录容量（相关维度不可判）</div>
      <ul>
        <li v-for="(s, i) in result.unratedNotes" :key="i">{{ s }}</li>
      </ul>
    </template>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as CapacityConflictApi from '@/api/transport/ai/capacityConflict'
import type { CapacityVehicle, CapacitySegment } from '@/api/transport/ai/capacityConflict'

defineOptions({ name: 'TransportAiCapacityConflict' })

const message = useMessage()

type VehicleRow = { label: string; passengerCapacity?: number; cargoCapacityItems?: number; cargoCapacityKg?: number }
type SegmentRow = { name: string; forecastPassenger?: number; forecastCargoItems?: number; forecastCargoKg?: number }

const sceneLabel = ref('')
const vehicles = ref<VehicleRow[]>([{ label: '' }])
const segments = ref<SegmentRow[]>([{ name: '' }])
const checkLoading = ref(false)
const errorMsg = ref('')
const result = ref<CapacityConflictApi.CapacityConflictCheckResp | null>(null)

const addVehicle = () => vehicles.value.push({ label: '' })
const removeVehicle = (idx: number) => {
  if (vehicles.value.length > 1) vehicles.value.splice(idx, 1)
}
const addSegment = () => segments.value.push({ name: '' })
const removeSegment = (idx: number) => {
  if (segments.value.length > 1) segments.value.splice(idx, 1)
}

const resetAll = () => {
  vehicles.value = [{ label: '' }]
  segments.value = [{ name: '' }]
  sceneLabel.value = ''
  result.value = null
  errorMsg.value = ''
}

const handleCheck = async () => {
  const validVehicles = vehicles.value.filter((v) => v.label.trim())
  const validSegments = segments.value.filter((s) => s.name.trim())
  if (!validVehicles.length || !validSegments.length) {
    message.warning('至少需要一辆带标识的车与一段带名称的预报量')
    return
  }
  checkLoading.value = true
  errorMsg.value = ''
  try {
    // 容量留空按「未录」处理：显式传 undefined（JSON 里省略 ⇒ 后端 null），不臆造成 0
    result.value = await CapacityConflictApi.checkCapacityConflict({
      sceneLabel: sceneLabel.value || undefined,
      vehicles: validVehicles as CapacityVehicle[],
      segments: validSegments as CapacitySegment[]
    })
  } catch (e: any) {
    // 失败先清结果再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有冲突」
    result.value = null
    errorMsg.value = '检视失败：' + (e?.msg || e?.message || '未知原因（这不是「没有冲突」）')
  } finally {
    checkLoading.value = false
  }
}
</script>
