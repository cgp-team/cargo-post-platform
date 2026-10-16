import type { AiCapabilityStatus } from '@/api/transport/ai/capability'

/**
 * 悬浮 AI 助手要不要挂载 —— **纯函数，正反两面都钉在 vitest 里**
 * （`src/views/transport/ai/__tests__/aiAssistantGate.test.ts`）。
 *
 * 契约 docs/AI_ASSIST_BOUNDARY.md §0/§7 的红线就落在这一行：
 * 能力没就绪时悬浮助手**整个组件不渲染**，而不是留一个点了没反应的圆按钮 ——
 * 后者正是本仓禁止的**假忙 / 演示形态**，也会破坏"关掉 AI 整套后台零变化"那条红线。
 *
 * 单独放在这个文件里、且只 import **类型**：自检（vitest node 环境）因此
 * 不用把 axios/整套前端配置拉进来跑，也不会因为两边 mock 不一致而"永远绿"。
 */
export const shouldMountAiAssistant = (status?: AiCapabilityStatus | null): boolean => status?.ready === true
