<template>
  <ContentWrap title="AI 辅助（只出参考意见）">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="这一页的每个结果都是草稿：后端不会替任何人改单、派车、发消息。要落地，请照建议去既有页面自己操作。"
    />

    <ContentWrap>
      <div class="mb-12px font-bold">1 · 上报语义分类 / 照片支撑度</div>
      <div class="flex flex-col gap-8px">
        <el-input v-model="form.text" placeholder="上报描述，如：路上塌方了，车过不去" />
        <el-input v-model="form.photoUrl" placeholder="现场照片地址（可空；只有要问「照片撑不撑得上这段描述」时才填）" />
        <div class="flex gap-8px flex-wrap">
          <el-button
            type="primary"
            :loading="busyLoading"
            v-hasPermi="['transport:ai:task:run']"
            @click="runClassify"
          >问一次分类建议</el-button>
          <el-button
            :loading="busyLoading"
            v-hasPermi="['transport:ai:task:run']"
            @click="runPhoto"
          >核对照片是否支撑</el-button>
        </div>
      </div>
    </ContentWrap>

    <ContentWrap>
      <div class="mb-12px font-bold">2 · 口语上报归并 / 反馈主题聚类</div>
      <div class="flex flex-col gap-8px">
        <el-input
          v-model="itemsText"
          type="textarea"
          :rows="4"
          placeholder="每行一条上报（至少两条才归并，一条请人工看）"
        />
        <div class="flex gap-8px flex-wrap items-center">
          <el-button :loading="busyLoading" v-hasPermi="['transport:ai:task:run']" @click="runMerge">
            归并这些上报
          </el-button>
          <span class="text-gray-500">反馈样本数</span>
          <el-input-number v-model="sampleCount" :min="1" :max="100000" style="width: 140px" />
          <el-button :loading="busyLoading" v-hasPermi="['transport:ai:task:run']" @click="runCluster">
            对上面这段文本做主题聚类
          </el-button>
        </div>
      </div>
    </ContentWrap>

    <ContentWrap>
      <div class="mb-12px font-bold">3 · 简报润色（数字一个都不许改）</div>
      <div class="flex flex-col gap-8px">
        <el-input
          v-model="form.text"
          type="textarea"
          :rows="4"
          placeholder="贴入简报事实底稿（Markdown 表格即可）"
        />
        <div>
          <el-button :loading="busyLoading" v-hasPermi="['transport:ai:task:run']" @click="runPolish">
            生成成稿并核对数字
          </el-button>
        </div>
      </div>
    </ContentWrap>

    <ContentWrap>
      <div class="mb-12px font-bold">4 · 情景提示（规则算的，不需要模型；关掉 AI 也能用）</div>
      <div class="flex gap-8px items-center flex-wrap">
        <span>日期</span>
        <el-date-picker v-model="hintDay" type="date" value-format="YYYY-MM-DD" style="width: 160px" />
        <el-button
          type="primary"
          :loading="hintLoading"
          v-hasPermi="['transport:ai:scenario:query']"
          @click="runScenario"
        >查这一天</el-button>
        <span class="text-gray-500">留空默认问明天（赶集要提前准备）</span>
      </div>
    </ContentWrap>

    <ContentWrap>
      <div class="mb-12px font-bold">结果</div>
      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" />
      <el-empty v-else-if="!result" description="还没问过。上面任一格点一次就有结果。" :image-size="60" />
      <template v-else>
        <el-alert
          v-if="!result.ready"
          type="warning"
          :closable="false"
          show-icon
          :title="'能力未就绪：' + (result.reason || '后端没给原因')"
          description="这一格没有产生任何 AI 输出 —— 页面不会拿模板话冒充模型结论。"
        />
        <el-alert
          v-else-if="!result.parsed"
          type="warning"
          :closable="false"
          show-icon
          :title="'模型回了，但没按格式：' + (result.reason || '解析不出结构')"
          description="所以这里不给建议。原文在下面，请自己判断，不要当成结论。"
        />
        <el-result v-else icon="info" :title="result.suggestion || '没有给出建议（依据不足）'">
          <template #sub-title>
            <div class="whitespace-pre-wrap text-left">{{ result.basis }}</div>
            <div v-if="result.numbersPreserved === false" class="text-red-500">
              ⚠ 成稿丢了底稿里的数字，这一版不要采纳。
            </div>
          </template>
          <template #extra>
            <div class="text-gray-500 text-left">
              采纳方式：复制下面这段到对应业务页面，由值班员自己执行 —— 本页不写任何业务数据。
            </div>
            <el-input
              v-if="result.rawText"
              class="mt-8px"
              :model-value="result.rawText"
              type="textarea"
              :rows="5"
              readonly
            />
          </template>
        </el-result>
      </template>
    </ContentWrap>
  </ContentWrap>
</template>

<script setup lang="ts">
import * as AiTaskApi from '@/api/transport/ai/aiTask'

defineOptions({ name: 'TransportAiAssist' })

const message = useMessage()

const form = reactive<AiTaskApi.AiTaskReqVO>({ text: '', photoUrl: '', categories: undefined, items: undefined, sampleCount: undefined, day: undefined })
const itemsText = ref('')
const sampleCount = ref(20)
const hintDay = ref('')

const busyLoading = ref(false)
const hintLoading = ref(false)
const error = ref('')
const result = ref<AiTaskApi.AiDraftVO | null>(null)

/**
 * 每次调用前先清结果：留着上一次的答案在屏幕上，下一次失败时就会被读成"这次也是这个答案"
 * （与后台那一族"失败先清数据再留字"的口径同一条，判据 scripts/check_admin_catch_leaves_data.py）。
 */
const ask = async (fn: () => Promise<AiTaskApi.AiDraftVO>, okWord: string) => {
  busyLoading.value = true
  error.value = ''
  result.value = null
  try {
    const res = await fn()
    result.value = res || null
    if (res?.ready && res?.parsed) {
      message.success(okWord)
    }
  } catch (e: any) {
    result.value = null
    error.value = '这一格没跑成：' + (e?.msg || e?.message || '未知原因（这不是「模型说没有」）')
  } finally {
    busyLoading.value = false
  }
}

const runClassify = () => {
  if (!form.text?.trim()) {
    message.warning('先填上报描述（空材料问出来的是编造）')
    return
  }
  return ask(() => AiTaskApi.classifyReport({ text: form.text, categories: form.categories }), '已给出分类建议（草稿）')
}

const runPhoto = () => {
  if (!form.text?.trim() || !form.photoUrl?.trim()) {
    message.warning('描述与照片地址都要有 —— 没照片时这一格不该问')
    return
  }
  return ask(() => AiTaskApi.checkPhotoSupport({ text: form.text, photoUrl: form.photoUrl }), '已给出核对意见（草稿）')
}

const runMerge = () => {
  const items = itemsText.value.split('\n').map((s) => s.trim()).filter((s) => s)
  if (items.length < 2) {
    message.warning('至少两条才有得归并（一条请人工看）')
    return
  }
  return ask(() => AiTaskApi.mergeReports({ items }), '已给出归并草稿')
}

const runCluster = () => {
  if (!form.text?.trim()) {
    message.warning('聚类吃的是反馈摘要文本，先填上面那个输入框')
    return
  }
  return ask(() => AiTaskApi.clusterFeedback({ text: form.text, sampleCount: sampleCount.value }), '已给出主题分布（供人工排期）')
}

const runPolish = () => {
  if (!form.text?.trim()) {
    message.warning('先贴入事实底稿')
    return
  }
  return ask(() => AiTaskApi.polishBriefing({ text: form.text }), '已生成成稿并核对过数字')
}

const runScenario = async () => {
  hintLoading.value = true
  error.value = ''
  result.value = null
  try {
    const day = hintDay.value || tomorrow()
    result.value = await AiTaskApi.getScenarioHints(day)
  } catch (e: any) {
    result.value = null
    error.value = '情景提示没读到：' + (e?.msg || e?.message || '未知原因（这不是「这一天没有事件」）')
  } finally {
    hintLoading.value = false
  }
}

const tomorrow = () => {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  return d.toISOString().slice(0, 10)
}
</script>
