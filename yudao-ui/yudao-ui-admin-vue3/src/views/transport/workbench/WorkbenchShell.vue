<template>
  <div class="workbench">
    <div class="workbench__head">
      <span class="workbench__title">{{ title }}</span>
      <span v-if="hint" class="workbench__hint">{{ hint }}</span>
    </div>

    <el-tabs v-if="visibleTabs.length" v-model="active" class="workbench__tabs">
      <el-tab-pane v-for="t in visibleTabs" :key="t.name" :label="t.label" :name="t.name" lazy>
        <component :is="t.component" />
      </el-tab-pane>
    </el-tabs>

    <el-empty
      v-else
      :image-size="60"
      description="这个工作台里每一格都要单独授权，当前账号一格都没有 —— 这里不给内容，也不编一份只读的样例。"
    />
  </div>
</template>

<script setup lang="ts">
import { checkPermi } from '@/utils/permission'
import type { WorkbenchTab } from './types'

/**
 * 后台页面的集成壳：把同域的几个页面收成一行 tab，一个菜单叶子进、多格出。
 *
 * 三条设计约束（都是被现有页面逼出来的，不是审美选择）：
 * · **原页面一行不改**，以子组件挂进来。它们各自带着自己的 `<ContentWrap>` 和取数逻辑，
 *   所以这一层刻意不再包 ContentWrap —— 否则会套出两张卡片、两个标题；
 * · `lazy` 是必需的：这些页面都在 `onMounted` 里拉列表，一次挂载七格就是七个并发请求，
 *   而用户一次只看一格；
 * · 活动格写进 `?tab=`，看板的快捷入口和"调度中心 → 拓扑"这类跳转才能落到具体那一格，
 *   而不是把人扔回工作台第一格再自己找。
 */
defineOptions({ name: 'TransportWorkbenchShell' })

const props = defineProps<{
  title: string
  hint?: string
  tabs: WorkbenchTab[]
}>()

const route = useRoute()
const router = useRouter()

const visibleTabs = computed(() =>
  props.tabs.filter((t) => !t.perms?.length || checkPermi(t.perms)),
)

const holds = (name: string) => visibleTabs.value.some((t) => t.name === name)
const firstTab = () => visibleTabs.value[0]?.name ?? ''

// 初值认 URL：/transport/capacity?tab=vehicle 要直接开在车辆那一格
const active = ref(
  holds(String(route.query.tab ?? '')) ? String(route.query.tab) : firstTab(),
)

watch(active, (name) => {
  if (!name || String(route.query.tab ?? '') === name) return
  router.replace({ query: { ...route.query, tab: name } })
})

// 权限是在登录之后才灌进 store 的，某些入口（如刷新直接进深链）首帧筛出来的格会更少
watch(visibleTabs, (tabs) => {
  if (!holds(active.value)) active.value = tabs[0]?.name ?? ''
})
</script>

<style scoped>
.workbench__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.workbench__title {
  font-size: 16px;
  font-weight: 600;
}

.workbench__hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/*
 * 七格的页面里，列表一翻页就上千行；tab 条跟着滚走意味着"换一格要先滚回顶部"。
 * 钉在顶上（`top: 0`），切换的成本才和只有一页时一样。
 * `background` 必须给实心：透明会在滚过时把下面的表格文字叠进 tab 标签里。
 */
.workbench__tabs :deep(.el-tabs__header) {
  position: sticky;
  top: 0;
  z-index: 3;
  margin-bottom: 12px;
  background: var(--el-bg-color);
}
</style>
