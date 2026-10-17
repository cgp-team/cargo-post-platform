import type { Component } from 'vue'

/**
 * 工作台里的一格 tab。
 *
 * 口径：工作台**不新建权限**，每一格挂的还是它原来那个页面菜单上的读权限。
 * 为什么要在前端按格筛：`WorkbenchShell` 只有一个菜单叶子、一个路由，
 * 若不做逐格筛，一个只有车辆权限的账号就会在这页上看到站点、司机、班次的格子，
 * 点进去才被接口层 403 —— 界面先承诺再报错，这是不该给用户体验的东西。
 * 真正的强制仍在服务端 `@PreAuthorize`，这一层只是不给出按不动的按钮位。
 */
export interface WorkbenchTab {
  /** 用于 `?tab=` 深链，写死不要改名：看板的快捷入口按这个值跳格 */
  name: string
  label: string
  /** 这一格的读权限；留空表示人人可见（目前没有这种格） */
  perms?: string[]
  component: Component
}
