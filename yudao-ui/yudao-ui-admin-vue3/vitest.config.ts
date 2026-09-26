import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// ENG-04: 前端单测最小配置。
// 范围仅限纯函数 / 契约测试（不含 Vue 组件挂载，避免引入 heavy 依赖）。
// 运行：corepack pnpm test
export default defineConfig({
  // 与 vite.config.ts 的 @ -> src 别名对齐（routerHelper 等源码使用 @/ 引用）
  resolve: {
    alias: [{ find: /^@\//, replacement: fileURLToPath(new URL('./src/', import.meta.url)) }]
  },
  test: {
    include: ['src/**/__tests__/**/*.test.ts'],
    environment: 'node',
    reporters: 'default'
  }
})
