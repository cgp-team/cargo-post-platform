import { describe, expect, it } from 'vitest'
import { filter, findNode, handleTree, listToTree, treeToList } from '../tree'

/**
 * ENG-04 纯函数测试：tree.ts 是站点树 / 部门树等场景的基础工具，
 * 站点管理（WEB 系列）依赖 listToTree 构造级联下拉，行为回归在此锁定。
 *
 * 注意：listToTree 会原地突变入参（为每个节点挂 children），
 * 且对同一份数组重复调用会导致 children 重复累积（不可重入）。
 * 因此每个测试用工厂函数生成独立数据，绝不能共享模块级常量。
 */
const makeFlat = () => [
  { id: 1, parentId: 0, name: '县中心站' },
  { id: 2, parentId: 1, name: 'A 村站' },
  { id: 3, parentId: 1, name: 'B 村站' },
  { id: 4, parentId: 2, name: 'A 村代收点' }
]

const TREE_CONFIG = { id: 'id', pid: 'parentId', children: 'children' }

describe('listToTree', () => {
  it('平铺列表按 parentId 构树，子节点挂到对应父节点', () => {
    const tree = listToTree<any>(makeFlat(), TREE_CONFIG)
    expect(tree).toHaveLength(1)
    const root = tree[0]
    expect(root.name).toBe('县中心站')
    expect(root.children).toHaveLength(2)
    const villageA = root.children.find((n: any) => n.id === 2)
    expect(villageA.children.map((n: any) => n.name)).toEqual(['A 村代收点'])
  })

  it('孤立节点（父不存在）视为根节点，不丢失', () => {
    const tree = listToTree<any>(
      [
        { id: 1, parentId: 0, name: 'root' },
        { id: 9, parentId: 999, name: 'orphan' }
      ],
      TREE_CONFIG
    )
    expect(tree.map((n: any) => n.name).sort()).toEqual(['orphan', 'root'])
  })

  it('空数组返回空数组', () => {
    expect(listToTree([], TREE_CONFIG)).toEqual([])
  })
})

describe('treeToList', () => {
  it('树还原为平铺列表，包含全部节点', () => {
    const tree = listToTree<any>(makeFlat(), TREE_CONFIG)
    const flat = treeToList(tree, { children: 'children' })
    expect(flat).toHaveLength(4)
    expect(flat.map((n: any) => n.id).sort()).toEqual([1, 2, 3, 4])
  })
})

describe('findNode', () => {
  it('命中深层节点', () => {
    const tree = listToTree<any>(makeFlat(), TREE_CONFIG)
    const node = findNode(tree, (n: any) => n.id === 4)
    expect(node?.name).toBe('A 村代收点')
  })

  it('未命中返回 null', () => {
    const tree = listToTree<any>(makeFlat(), TREE_CONFIG)
    expect(findNode(tree, (n: any) => n.id === 88)).toBeNull()
  })
})

describe('handleTree（yudao 默认字段）', () => {
  it('默认 id/parentId 字段构树', () => {
    const tree = handleTree(makeFlat())
    expect(tree).toHaveLength(1)
    expect(tree[0].children).toHaveLength(2)
    expect(tree[0].children[0].children[0].name).toBe('A 村代收点')
  })

  it('非数组入参安全返回空数组', () => {
    expect(handleTree(null as any)).toEqual([])
  })
})

describe('filter', () => {
  it('保留命中节点及其祖先链', () => {
    const tree = listToTree<any>(makeFlat(), TREE_CONFIG)
    const result = filter(tree, (n: any) => n.name.includes('代收'))
    // 命中叶子 → 祖先链县中心站 → A 村站保留
    expect(result).toHaveLength(1)
    expect(result[0].name).toBe('县中心站')
    expect(result[0].children[0].children[0].name).toBe('A 村代收点')
  })
})
