<template>
  <!-- 占位：菜单路由挂在 Layout 下，挂载时立即跳转到全屏大屏路由 -->
  <div />
</template>

<script lang="ts" setup>
// 侧边栏「智慧大屏」菜单入口：
// 动态菜单路由带 Layout（/transport/bigscreen-entry），
// 这里立即 replace 到 remaining.ts 中的全屏路由 /transport/bigscreen（无 Layout、1920×1080 等比缩放）。
// 注意：该菜单行 keep_alive 必须为 b'0'，否则组件被 keep-alive 缓存后不会再执行跳转。
const router = useRouter()

router.replace('/transport/bigscreen')
</script>
