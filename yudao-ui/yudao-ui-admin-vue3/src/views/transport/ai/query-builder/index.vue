<template>
  <ContentWrap title="查询构造器（存条件）">
    <el-form :inline="true" :model="queryParams" @submit.prevent="handleQuery">
      <el-form-item label="名称">
        <el-input v-model="queryParams.name" placeholder="请输入名称" clearable @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="场景">
        <el-select v-model="queryParams.scene" placeholder="请选择场景" clearable style="width: 160px">
          <el-option v-for="item in SCENE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleQuery"><Icon icon="ep:search" />搜索</el-button>
        <el-button @click="resetQuery"><Icon icon="ep:refresh" />重置</el-button>
      </el-form-item>
    </el-form>
  </ContentWrap>

  <ContentWrap>
    <el-button type="primary" v-hasPermi="['transport:ai:saved-query:create']" @click="openForm('create')">
      <Icon icon="ep:plus" />新增条件
    </el-button>
    <el-alert
      v-if="listError"
      type="error"
      :closable="false"
      show-icon
      :title="listError"
      description="这张空表只说明这次请求没成功；下面的「暂无数据」不是业务结论。"
    />
    <el-table v-loading="loading" :data="list" stripe border style="margin-top: 16px">
      <el-table-column label="名称" prop="name" align="center" min-width="140" />
      <el-table-column label="场景" align="center" width="100">
        <template #default="scope">{{ labelOfScene(scope.row.scene) }}</template>
      </el-table-column>
      <el-table-column label="筛选条件" prop="conditionsJson" align="center" show-overflow-tooltip min-width="240" />
      <el-table-column label="备注" prop="remark" align="center" show-overflow-tooltip />
      <el-table-column label="操作" align="center" width="150">
        <template #default="scope">
          <el-button
            link
            type="primary"
            v-hasPermi="['transport:ai:saved-query:update']"
            @click="openForm('update', scope.row.id)"
            >编辑</el-button
          >
          <el-button
            link
            type="danger"
            v-hasPermi="['transport:ai:saved-query:delete']"
            @click="handleDelete(scope.row.id)"
            >删除</el-button
          >
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :image-size="60" description="暂无保存的条件" />
      </template>
    </el-table>
    <Pagination
      :total="total"
      v-model:page="queryParams.pageNo"
      v-model:limit="queryParams.pageSize"
      @pagination="getList"
    />
  </ContentWrap>

  <ContentWrap title="运营简报（模板导出）">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="把已取到的指标排成一份 Markdown"
      description="数值请用文字填写：缺值写「未取到」，不要留空或写 0。本页只排版，不改任何业务数据。"
    />
    <el-form label-width="90px" style="margin-top: 12px">
      <el-form-item label="标题">
        <el-input v-model="briefing.title" placeholder="如：石桥镇 · 早班运营简报" />
      </el-form-item>
      <el-form-item label="统计周期">
        <el-input v-model="briefing.periodLabel" placeholder="如：2026-10-14 ~ 2026-10-20" />
      </el-form-item>
    </el-form>
    <el-table :data="briefing.metrics" border stripe>
      <el-table-column label="指标名" min-width="160">
        <template #default="scope"><el-input v-model="scope.row.name" placeholder="如：未分配订单数" /></template>
      </el-table-column>
      <el-table-column label="数值/文字" min-width="150">
        <template #default="scope"><el-input v-model="scope.row.value" placeholder="缺值请写「未取到」" /></template>
      </el-table-column>
      <el-table-column label="单位" width="110">
        <template #default="scope"><el-input v-model="scope.row.unit" placeholder="如：单" /></template>
      </el-table-column>
      <el-table-column label="备注" min-width="160">
        <template #default="scope"><el-input v-model="scope.row.note" placeholder="选填" /></template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="scope">
          <el-button link type="danger" @click="removeMetric(scope.$index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button style="margin-top: 12px" @click="addMetric"><Icon icon="ep:plus" />添加指标</el-button>
    <el-form label-width="90px" style="margin-top: 12px">
      <el-form-item label="文字备注">
        <el-input
          v-model="briefing.notesText"
          type="textarea"
          :rows="3"
          placeholder="一行一条备注（选填）"
        />
      </el-form-item>
    </el-form>
    <el-button type="primary" :loading="renderLoading" @click="handleRender">
      <Icon icon="ep:document" />生成简报
    </el-button>
    <el-alert
      v-if="renderError"
      type="error"
      :closable="false"
      show-icon
      :title="renderError"
      description="生成失败，下面的文本不能当成简报结果。"
    />
    <el-input
      v-if="briefingResult"
      v-model="briefingResult.markdown"
      type="textarea"
      :rows="14"
      readonly
      style="margin-top: 12px"
    />
  </ContentWrap>

  <QueryBuilderForm ref="formRef" @success="getList" />
</template>

<script setup lang="ts">
import * as SavedQueryApi from '@/api/transport/ai/savedQuery'
import type { BriefingMetric } from '@/api/transport/ai/savedQuery'
import QueryBuilderForm from './QueryBuilderForm.vue'

defineOptions({ name: 'TransportAiQueryBuilder' })

const SCENE_OPTIONS = [
  { label: '订单', value: 'ORDER' },
  { label: '调度', value: 'DISPATCH' },
  { label: '反馈', value: 'FEEDBACK' }
]

const message = useMessage()
const loading = ref(true)
const listError = ref('')
const total = ref(0)
const list = ref<SavedQueryApi.AiSavedQueryVO[]>([])
type QueryParams = { pageNo: number; pageSize: number; name?: string; scene?: string }
const queryParams = reactive<QueryParams>({ pageNo: 1, pageSize: 10, name: '', scene: undefined })
const formRef = ref()

const labelOfScene = (value: string) => SCENE_OPTIONS.find((item) => item.value === value)?.label ?? value

const getList = async () => {
  loading.value = true
  try {
    const res = await SavedQueryApi.getSavedQueryPage(queryParams)
    list.value = res.list
    total.value = res.total
  } catch (e: any) {
    // 失败必须先清数据再留字：清掉是防「还在念上一次」，留字是防「空白被读成没有」
    list.value = []
    total.value = 0
    listError.value = '列表没读到：' + (e?.msg || e?.message || '未知原因（这不是「没有数据」）')
  } finally {
    loading.value = false
  }
}
const handleQuery = () => {
  queryParams.pageNo = 1
  getList()
}
const resetQuery = () => {
  Object.assign(queryParams, { pageNo: 1, pageSize: 10, name: '', scene: undefined })
  getList()
}
const openForm = (type: string, id?: number) => formRef.value?.open(type, id)
const handleDelete = async (id: number) => {
  try {
    await message.confirm('确认删除该查询条件？删除后不影响已生成的简报。')
    await SavedQueryApi.deleteSavedQuery(id)
    message.success('删除成功')
    getList()
  } catch (e) {
    /* cancelled */
  }
}

// ---- 运营简报 ----
type BriefingState = { title: string; periodLabel: string; metrics: BriefingMetric[]; notesText: string }
const briefing = reactive<BriefingState>({
  title: '',
  periodLabel: '',
  metrics: [{ name: '', value: '', unit: '', note: '' }],
  notesText: ''
})
const renderLoading = ref(false)
const renderError = ref('')
const briefingResult = ref<SavedQueryApi.BriefingResp | null>(null)

const addMetric = () => briefing.metrics.push({ name: '', value: '', unit: '', note: '' })
const removeMetric = (idx: number) => {
  if (briefing.metrics.length > 1) briefing.metrics.splice(idx, 1)
}

const handleRender = async () => {
  const metrics = briefing.metrics.filter((m) => m.name.trim() && m.value.trim())
  if (!briefing.title.trim()) {
    message.warning('请填写简报标题')
    return
  }
  if (!metrics.length) {
    message.warning('至少填写一项指标（含指标名与数值）')
    return
  }
  renderLoading.value = true
  renderError.value = ''
  try {
    briefingResult.value = await SavedQueryApi.renderBriefing({
      title: briefing.title,
      periodLabel: briefing.periodLabel || undefined,
      metrics,
      notes: briefing.notesText
        .split('\n')
        .map((s) => s.trim())
        .filter((s) => s)
    })
  } catch (e: any) {
    // 失败先清结果再留字：别让上一次的简报文本留在屏幕上冒充这一次的结果
    briefingResult.value = null
    renderError.value = '生成失败：' + (e?.msg || e?.message || '未知原因（这不是一份简报）')
  } finally {
    renderLoading.value = false
  }
}

onMounted(getList)
</script>
