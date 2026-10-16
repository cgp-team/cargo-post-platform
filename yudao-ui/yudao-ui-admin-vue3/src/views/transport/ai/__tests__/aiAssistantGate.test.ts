import { describe, expect, it } from 'vitest'
import type { AiCapabilityStatus } from '@/api/transport/ai/capability'
import { shouldMountAiAssistant } from '../aiAssistantGate'

/**
 * 悬浮 AI 助手的挂载闸（契约 docs/AI_ASSIST_BOUNDARY.md §0 / §7）。
 *
 * 这把尺子钉的是本仓那条红线：**能力没就绪就整个组件不渲染**。
 * 反面用例（未就绪必须不挂载）比正面用例重要 —— 少了它，"永远挂载"也能全绿。
 */
const status = (over: Partial<AiCapabilityStatus> = {}): AiCapabilityStatus => ({
  enabled: false,
  endpointConfigured: false,
  ready: false,
  reason: '能力未就绪：开关 buspost.ai.enabled 未打开（服务端未启用 AI）。',
  ...over
})

describe('shouldMountAiAssistant 悬浮助手挂载闸', () => {
  it('能力就绪 ⇒ 挂载（否则功能存在却没人进得去）', () => {
    expect(
      shouldMountAiAssistant(status({ enabled: true, endpointConfigured: true, ready: true, reason: '' })),
      '已就绪却不挂载 ⇒ 悬浮助手永远不出现'
    ).toBe(true)
  })

  it('开关没开 ⇒ 不挂载（关掉 AI 整套后台必须零变化）', () => {
    expect(shouldMountAiAssistant(status()), '未就绪还挂载 ⇒ 就是本仓禁止的假忙/演示形态').toBe(false)
  })

  it('基址/密钥配了但 ready=false ⇒ 仍不挂载（只认 ready 这一个真值）', () => {
    expect(
      shouldMountAiAssistant(status({ enabled: true, endpointConfigured: true, ready: false, reason: '模型名为空' })),
      '自己推断 ready 会让"配了一半"的系统挂着一个点不动的按钮'
    ).toBe(false)
  })

  it('状态接口没回（null/undefined，探针失败）⇒ 不挂载（fail closed）', () => {
    expect(shouldMountAiAssistant(null), '拿不到状态时不许自作主张挂上').toBe(false)
    expect(shouldMountAiAssistant(undefined)).toBe(false)
  })

  it('对象里多带字段也不影响判定（只看 ready）', () => {
    expect(shouldMountAiAssistant({ ...status(), model: 'qwen-plus' } as AiCapabilityStatus)).toBe(false)
    expect(shouldMountAiAssistant({ ...status(), ready: true, reason: '' } as AiCapabilityStatus)).toBe(true)
  })
})
