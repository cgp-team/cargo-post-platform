import type { App } from 'vue'
import { useUserStoreWithOut } from '@/store/modules/user'

const { t } = useI18n() // 国际化

/** 判断权限的指令 directive */
export function hasPermi(app: App<Element>) {
  app.directive('hasPermi', (el, binding) => {
    const { value } = binding

    if (value && value instanceof Array && value.length > 0) {
      const hasPermissions = hasPermission(value)

      if (!hasPermissions) {
        el.parentNode && el.parentNode.removeChild(el)
      }
    } else {
      throw new Error(t('permission.hasPermission'))
    }
  })
}

/** 判断权限的方法 function */
// 必须用 WithOut（显式传入 pinia 实例）：本行在模块顶层执行，早于 main.ts 的 app.use(pinia)，
// 裸调 useUserStore() 会因 activePinia 未激活而在生产构建直接抛 `pinia._s` TypeError。
const userStore = useUserStoreWithOut()
const all_permission = '*:*:*'
export const hasPermission = (permission: string[]) => {
  return (
    userStore.permissions.has(all_permission) ||
    permission.some((permission) => userStore.permissions.has(permission))
  )
}
