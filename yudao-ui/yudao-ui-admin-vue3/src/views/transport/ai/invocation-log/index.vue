<template>
  <ContentWrap title="AI 调用日志">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="每次 AI 调用的可见留痕"
      description="本页只读：这张表由后端写、运维查，页面没有删除口。留痕失败不会阻断业务（旁路），但失败本身会记 error 级日志。"
    />
    <el-form :inline="true" :model="queryParams" style="margin-top: 12px" @submit.prevent="handleQuery">
      <el-form-item label="场景">
        <el-select v-model="queryParams.scene" placeholder="请选择场景" clearable style="width: 160px">
          <el-option v-for="item in SCENE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="queryParams.status" placeholder="请选择状态" clearable style="width: 140px">
          <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="操作人编号">
        <el-input v-model="operatorIdInput" placeholder="如：1" clearable style="width: 140px" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
        <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap>
    <el-alert
      v-if="listError"
      type="error"
      :closable="false"
      show-icon
      :title="listError"
      description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
    />
    <el-table v-loading="loading" :data="list" stripe border>
      <el-table-column label="编号" prop="id" align="center" width="80" />
      <el-table-column label="场景" align="center" width="130">
        <template #default="scope">{{ labelOfScene(scope.row.scene) }}</template>
      </el-table-column>
      <el-table-column label="操作人" prop="operatorId" align="center" width="100" />
      <el-table-column label="状态" align="center" width="120">
        <template #default="scope">
          <el-tag :type="tagTypeOfStatus(scope.row.status)">{{ scope.row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="送入(已脱敏)" prop="maskedInput" align="center" show-overflow-tooltip min-width="200" />
      <el-table-column label="返回" prop="outputText" align="center" show-overflow-tooltip min-width="220" />
      <el-table-column label="token" prop="tokens" align="center" width="90" />
      <el-table-column label="耗时(ms)" prop="latencyMs" align="center" width="100" />
      <el-table-column label="失败要点" prop="errorBrief" align="center" show-overflow-tooltip min-width="160" />
      <el-table-column label="时间" prop="createTime" align="center" width="170" />
      <template #empty>
        <el-empty :image-size="60" description="暂无调用记录（未就绪时为空是正常的）" />
      </template>
    </el-table>
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </ContentWrap>
</template>

<script setup lang="ts">
import * as InvocationLogApi from '@/api/transport/ai/invocationLog'

defineOptions({ name: 'TransportAiInvocationLog' })

const SCENE_OPTIONS = [
  { label: '自然语言查询', value: 'nl-query' },
  { label: '工单检视', value: 'ticket-review' },
  { label: '运力简报', value: 'capacity-brief' },
  { label: '风险卡片', value: 'risk-card' },
  { label: '运营摘要', value: 'digest' }
]
const STATUS_OPTIONS = [
  { label: '正常', value: 'OK' },
  { label: '限流', value: 'RATE_LIMITED' },
  { label: '错误', value: 'ERROR' },
  { label: '空回', value: 'EMPTY' }
]

const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<InvocationLogApi.AiInvocationLogVO[]>([])
const operatorIdInput = ref('')
type QueryParams = { pageNo: number; pageSize: number; scene?: string; status?: string; operatorId?: number }
const queryParams = reactive<QueryParams>({ pageNo: 1, pageSize: 10, scene: undefined, status: undefined, operatorId: undefined })

const labelOfScene = (value: string) => SCENE_OPTIONS.find((item) => item.value === value)?.label ?? value
const tagTypeOfStatus = (value: string) => {
  if (value === 'OK') return 'success'
  if (value === 'ERROR') return 'danger'
  if (value === 'RATE_LIMITED') return 'warning'
  return 'info'
}

const getList = async () => {
  loading.value = true
  try {
    const res = await InvocationLogApi.getInvocationLogPage(queryParams)
    list.value = res.list
    total.value = res.total
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有记录」）')
  } finally {
    loading.value = false
  }
}
const handleQuery = () => {
  queryParams.pageNo = 1
  const parsed = Number(operatorIdInput.value)
  queryParams.operatorId = operatorIdInput.value.trim() && Number.isFinite(parsed) ? parsed : undefined
  getList()
}
const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, scene: undefined, status: undefined, operatorId: undefined })
  operatorIdInput.value = ''
  getList()
}

onMounted(getList)
</script>
