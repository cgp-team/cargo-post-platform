import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { generateRoute } from '../routerHelper'

/**
 * 顶级菜单路径归一化回归：
 * V022 会员中心 path='member'（无前导斜杠），vue-router 5 注册顶级路由时抛
 * Invalid path，中断 permission.ts 的 addRoute forEach，导致登录后路由表残缺、页面卡死。
 */
const memberMenu = () => [
  {
    id: 7000,
    name: '会员中心',
    path: 'member',
    parentId: 0,
    visible: true,
    keepAlive: true,
    alwaysShow: true,
    children: [
      {
        id: 7001,
        name: '会员列表',
        path: 'user',
        parentId: 7000,
        component: 'member/user/index',
        componentName: 'MemberUser',
        visible: true,
        keepAlive: true
      }
    ]
  }
]

describe('generateRoute 顶级路径归一化', () => {
  it("相对路径顶级目录 'member' 注册为 '/member'，addRoute 不抛错", () => {
    const routes = generateRoute(memberMenu() as any)
    expect(routes[0].path).toBe('/member')

    const router = createRouter({ history: createMemoryHistory(), routes: [] })
    expect(() => routes.forEach((r) => router.addRoute(r as any))).not.toThrow()

    const resolved = router.resolve('/member/user')
    expect(resolved.matched.length).toBeGreaterThan(0)
  })

  it('已带前导斜杠的顶级目录保持原样', () => {
    const transport = [
      { id: 6800, name: '客货邮管理', path: '/transport', parentId: 0, visible: true, children: [] }
    ]
    const routes = generateRoute(transport as any)
    expect(routes[0].path).toBe('/transport')
  })
})
