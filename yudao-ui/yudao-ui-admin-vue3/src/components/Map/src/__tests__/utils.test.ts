import { describe, expect, it } from 'vitest'
import {
  BIGSCREEN_MAP_STYLE,
  CLUSTER_MIN_POINTS,
  bd09ToGcj02,
  clusterByGrid,
  clusterBubbleStyle,
  gcj02ToBd09,
  shouldCluster
} from '../utils'

/**
 * ENG-04 / WEB-14 / SEP-05：地图工具纯函数测试。
 * 重点覆盖 ①坐标系往返精度（站点"标偏 500m"事故的防线）
 * ②像素网格聚合分桶行为（500+ marker 不卡顿的核心算法）。
 */

describe('坐标系转换', () => {
  it('GCJ-02 → BD-09 往返误差在 1e-6 度量级（<0.2m）', () => {
    const samples = [
      { lng: 106.5765, lat: 29.5325 }, // 重庆南山
      { lng: 107.5, lat: 26.6 }, // 黔南山区
      { lng: 116.404, lat: 39.915 } // 北京
    ]
    for (const s of samples) {
      const bd = gcj02ToBd09(s.lng, s.lat)
      const back = bd09ToGcj02(bd.lng, bd.lat)
      expect(Math.abs(back.lng - s.lng)).toBeLessThan(1e-6)
      expect(Math.abs(back.lat - s.lat)).toBeLessThan(1e-6)
    }
  })

  it('BD-09 与 GCJ-02 存在可见偏移（未转换直接上图会偏数百米）', () => {
    const gcj = { lng: 106.5765, lat: 29.5325 }
    const bd = gcj02ToBd09(gcj.lng, gcj.lat)
    expect(Math.abs(bd.lng - gcj.lng)).toBeGreaterThan(0.004)
    expect(Math.abs(bd.lat - gcj.lat)).toBeGreaterThan(0.001)
  })
})

describe('clusterByGrid（像素网格聚合）', () => {
  // 测试用 identity 投影：把经纬度直接当像素坐标，便于构造网格
  const identity = (p: { lng: number; lat: number }) => ({ x: p.lng, y: p.lat })

  it('同一网格内的多个点合并为一个桶', () => {
    const buckets = clusterByGrid(
      [
        { lng: 10, lat: 20 },
        { lng: 20, lat: 30 },
        { lng: 200, lat: 300 }
      ],
      identity,
      64
    )
    expect(buckets).toHaveLength(2)
    const merged = buckets.find((b) => b.items.length === 2)
    expect(merged).toBeTruthy()
    // 桶位置取均值
    expect(merged!.lng).toBe(15)
    expect(merged!.lat).toBe(25)
  })

  it('跨网格边界的点不会被合并（floor 分桶语义）', () => {
    const buckets = clusterByGrid(
      [
        { lng: 63, lat: 10 },
        { lng: 65, lat: 10 }
      ],
      identity,
      64
    )
    expect(buckets).toHaveLength(2)
  })

  it('单点桶保留（由调用方决定画单车还是气泡）', () => {
    const buckets = clusterByGrid([{ lng: 5, lat: 5 }], identity, 64)
    expect(buckets).toHaveLength(1)
    expect(buckets[0].items).toHaveLength(1)
    expect(buckets[0].lng).toBe(5)
  })

  it('聚合位置为增量均值，三点也正确（防增量公式写错）', () => {
    const buckets = clusterByGrid(
      [
        { lng: 0, lat: 0 },
        { lng: 30, lat: 60 },
        { lng: 60, lat: 30 }
      ],
      identity,
      64
    )
    expect(buckets).toHaveLength(1)
    expect(buckets[0].lng).toBeCloseTo(30, 10)
    expect(buckets[0].lat).toBeCloseTo(30, 10)
  })

  it('空输入返回空数组', () => {
    expect(clusterByGrid([], identity)).toEqual([])
  })

  it('保留原始业务对象引用（供点击气泡定位/取车牌）', () => {
    const v = { lng: 1, lat: 2, plateNo: '渝A00001' }
    const buckets = clusterByGrid([v], identity, 64)
    expect(buckets[0].items[0]).toBe(v)
  })
})

describe('shouldCluster 阈值', () => {
  it('不超过阈值时不聚合（少量点位标签可读性优先）', () => {
    expect(shouldCluster(CLUSTER_MIN_POINTS)).toBe(false)
    expect(shouldCluster(10)).toBe(false)
  })

  it('超过阈值启用聚合', () => {
    expect(shouldCluster(CLUSTER_MIN_POINTS + 1)).toBe(true)
    expect(shouldCluster(500)).toBe(true)
  })
})

describe('clusterBubbleStyle', () => {
  it('数量越多颜色越深（三阶区分）', () => {
    expect(clusterBubbleStyle(2).backgroundColor).toBe('#2E7BBF')
    expect(clusterBubbleStyle(10).backgroundColor).toBe('#1F5E9E')
    expect(clusterBubbleStyle(50).backgroundColor).toBe('#123F6E')
  })

  it('白字 + 圆形，保证在暗色/浅色底图上都可读', () => {
    const style = clusterBubbleStyle(12)
    expect(style.color).toBe('#FFFFFF')
    expect(style.borderRadius).toBe('50%')
    expect(style.width).toBe(style.height)
  })
})

describe('BIGSCREEN_MAP_STYLE（SEP-05 暗色底图）', () => {
  it('覆盖背景/水系/道路/文字四类关键要素', () => {
    const features = BIGSCREEN_MAP_STYLE.styleJson.map((s) => s.featureType)
    for (const key of ['background', 'water', 'highway', 'administrative']) {
      expect(features).toContain(key)
    }
  })

  it('背景为深蓝墨（大屏暗色调）', () => {
    const bg = BIGSCREEN_MAP_STYLE.styleJson.find((s) => s.featureType === 'background')
    expect(bg?.stylers.color).toBe('#0B1220')
  })
})
