import type { AiDraftVO } from '@/api/transport/ai/aiTask'
import { getAiCapabilityStatus } from '@/api/transport/ai/capability'
import { shouldMountAiAssistant } from '@/views/transport/ai/aiAssistantGate'

/**
 * 全站只问一次能力状态（模块级缓存）。
 *
 * 为什么可以缓存死：`buspost.ai.*` 是**启动时的静态配置**，改它必须重启后端，
 * 会话期间 `ready` 不会翻转 —— 于是每次进页面重复问就是白发一个请求。
 * 问失败也缓存成 `false`（fail closed）：拿不到状态就不挂载，诊断留给「能力状态」那一页。
 */
let capabilityProbe: Promise<boolean> | null = null

export const probeAiAssistantReady = (): Promise<boolean> => {
  if (!capabilityProbe) {
    capabilityProbe = getAiCapabilityStatus()
      .then((status) => shouldMountAiAssistant(status))
      .catch(() => false)
  }
  return capabilityProbe
}

/** 只给自检/重载用：清掉缓存（正常路径不要调用）。 */
export const resetAiAssistantProbe = (): void => {
  capabilityProbe = null
}

/**
 * 一次 AI 询问的状态机，页面与悬浮助手**共用同一份实现**（契约 §7 只出草稿的落点）。
 *
 * 三条纪律（搬的就是 assist 页原来那份，别改散了）：
 * 1. 每次调用前先清结果 —— 留着上一次的答案在屏幕上，下一次失败时就会被读成"这次也是这个答案"；
 * 2. `ready && !parsed` 与 `!ready` 分两档显示，不许把"模型没按格式回"说成"没有建议"；
 * 3. 结果标题按场景分词：情景提示那一格是确切的空集，不能硬扣「依据不足」的帽子。
 */
export const useAiDraft = () => {
  const busyLoading = ref(false)
  const error = ref('')
  const result = ref<AiDraftVO | null>(null)

  const ask = async (fn: () => Promise<AiDraftVO>, okWord: string) => {
    busyLoading.value = true
    error.value = ''
    result.value = null
    try {
      const res = await fn()
      result.value = res || null
      if (res?.ready && res?.parsed) {
        useMessage().success(okWord)
      }
    } catch (e: any) {
      result.value = null
      error.value = '这一格没跑成：' + (e?.msg || e?.message || '未知原因（这不是「模型说没有」）')
    } finally {
      busyLoading.value = false
    }
  }

  const resultTitle = computed(() => {
    const r = result.value
    if (!r) return ''
    if (r.suggestion) return r.suggestion
    if (r.scene === 'scenario-capacity-hint') return '这一天没有可给的留舱提示'
    return '没有给出建议（依据不足）'
  })

  const reset = () => {
    busyLoading.value = false
    error.value = ''
    result.value = null
  }

  return { busyLoading, error, result, ask, resultTitle, reset }
}
