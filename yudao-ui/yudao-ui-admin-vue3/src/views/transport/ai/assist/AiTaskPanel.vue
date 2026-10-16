<template>
  <div>
    <ContentWrap>
      <div class="mb-12px font-bold">1 · 上报语义分类 / 照片支撑度</div>
      <div class="flex flex-col gap-8px">
        <el-input v-model="reportText" placeholder="上报描述，如：路上塌方了，车过不去" />
        <el-input v-model="photoUrl" placeholder="现场照片地址（可空；只有要问「照片撑不撑得上这段描述」时才填）" />
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
            对这段反馈做主题聚类
          </el-button>
        </div>
        <el-input
          v-model="feedbackText"
          type="textarea"
          :rows="2"
          placeholder="反馈摘要（聚类只吃这一格，不与上面的上报描述共用）"
        />
      </div>
    </ContentWrap>

    <ContentWrap>
      <div class="mb-12px font-bold">3 · 简报润色（数字一个都不许改）</div>
      <div class="flex flex-col gap-8px">
        <el-input
          v-model="briefingText"
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
        <el-result v-else icon="info" :title="resultTitle">
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
  </div>
</template>

<script setup lang="ts">
import * as AiTaskApi from '@/api/transport/ai/aiTask'
import { useAiDraft } from '@/hooks/web/useAiDraft'

defineOptions({ name: 'AiTaskPanel' })

/**
 * 六格 AI 辅助面板：**「AI 辅助任务」页与右下角悬浮助手挂的是同一份组件**。
 *
 * 为什么要抽出来而不是让悬浮窗再写一遍：两份实现必然漂移，而漂移点正好是
 * "草稿只出、未就绪就说未就绪"这几条最容易破的纪律 —— 一处改，两处都跟着变。
 *
 * 三格各用各的输入（浏览器点验时抓到的缺陷，别改回共用）：三格共用一个 model 会让
 * 第 1 格的字同时出现在第 3 格，聚类还会误吃上报描述。
 */
const { busyLoading, error, result, ask, resultTitle } = useAiDraft()

const reportText = ref('')
const feedbackText = ref('')
const briefingText = ref('')
const photoUrl = ref('')
const itemsText = ref('')
const sampleCount = ref(20)
const hintDay = ref('')
const hintLoading = ref(false)

const runClassify = () => {
  if (!reportText.value.trim()) {
    useMessage().warning('先填上报描述（空材料问出来的是编造）')
    return
  }
  return ask(() => AiTaskApi.classifyReport({ text: reportText.value }), '已给出分类建议（草稿）')
}

const runPhoto = () => {
  if (!reportText.value.trim() || !photoUrl.value.trim()) {
    useMessage().warning('描述与照片地址都要有 —— 没照片时这一格不该问')
    return
  }
  return ask(() => AiTaskApi.checkPhotoSupport({ text: reportText.value, photoUrl: photoUrl.value }), '已给出核对意见（草稿）')
}

const runMerge = () => {
  const items = itemsText.value.split('\n').map((s) => s.trim()).filter((s) => s)
  if (items.length < 2) {
    useMessage().warning('至少两条才有得归并（一条请人工看）')
    return
  }
  return ask(() => AiTaskApi.mergeReports({ items }), '已给出归并草稿')
}

const runCluster = () => {
  if (!feedbackText.value.trim()) {
    useMessage().warning('聚类吃的是「反馈摘要」那一格，先填它（不是上面的上报描述）')
    return
  }
  return ask(() => AiTaskApi.clusterFeedback({ text: feedbackText.value, sampleCount: sampleCount.value }), '已给出主题分布（供人工排期）')
}

const runPolish = () => {
  if (!briefingText.value.trim()) {
    useMessage().warning('先贴入事实底稿')
    return
  }
  return ask(() => AiTaskApi.polishBriefing({ text: briefingText.value }), '已生成成稿并核对过数字')
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
