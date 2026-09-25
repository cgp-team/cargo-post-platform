import { defineConfig } from 'vitest/config'

// ENG-04: 前端单测最小配置。
// 范围仅限纯函数 / 契约测试（不含 Vue 组件挂载，避免引入 heavy 依赖）。
// 运行：corepack pnpm test
export default defineConfig({
  test: {
    include: ['src/**/__tests__/**/*.test.ts'],
    environment: 'node',
    reporters: 'default'
  }
})
