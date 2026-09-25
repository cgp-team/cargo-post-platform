/**
 * 百度地图 SDK 加载工具 + 坐标系转换
 */

// 扩展 Window 接口以包含百度地图 GL API
declare global {
  interface Window {
    BMapGL: any
  }
}

// 全局回调名称
const CALLBACK_NAME = '__BAIDU_MAP_LOAD_CALLBACK__'

// SDK 加载状态
let loadPromise: Promise<void> | null = null

/**
 * 加载百度地图 GL SDK
 * @param timeout 超时时间（毫秒），默认 10000
 * @returns Promise<void>
 */
export const loadBaiduMapSdk = (timeout = 10000): Promise<void> => {
  // 已加载完成
  if (window.BMapGL) {
    return Promise.resolve()
  }

  // 正在加载中，返回同一个 Promise
  if (loadPromise) {
    return loadPromise
  }

  loadPromise = new Promise((resolve, reject) => {
    const timeoutId = setTimeout(() => {
      loadPromise = null
      reject(new Error('百度地图 SDK 加载超时'))
    }, timeout)

    // 全局回调
    ;(window as any)[CALLBACK_NAME] = () => {
      clearTimeout(timeoutId)
      delete (window as any)[CALLBACK_NAME]
      resolve()
    }

    // 创建 script 标签
    const script = document.createElement('script')
    script.src = `https://api.map.baidu.com/api?v=1.0&type=webgl&ak=${
      import.meta.env.VITE_BAIDU_MAP_KEY
    }&callback=${CALLBACK_NAME}`
    script.onerror = () => {
      clearTimeout(timeoutId)
      loadPromise = null
      delete (window as any)[CALLBACK_NAME]
      reject(new Error('百度地图 SDK 加载失败'))
    }
    document.body.appendChild(script)
  })

  return loadPromise
}

// ==================== 坐标系转换（业务数据是 GCJ-02，百度底图是 BD-09） ====================

const X_PI = (Math.PI * 3000.0) / 180.0

/**
 * GCJ-02 → BD-09。
 * 项目里站点/车辆/线路坐标全部是 GCJ-02（高德与微信小程序同一坐标系），
 * 直接画到百度底图上会整体偏移约 500m，必须转换后再上图。
 */
export const gcj02ToBd09 = (lng: number, lat: number) => {
  const z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI)
  const theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI)
  return { lng: z * Math.cos(theta) + 0.0065, lat: z * Math.sin(theta) + 0.006 }
}

/** BD-09 → GCJ-02（地图选点回填业务坐标用） */
export const bd09ToGcj02 = (bdLng: number, bdLat: number) => {
  const x = bdLng - 0.0065
  const y = bdLat - 0.006
  const z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI)
  const theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI)
  return { lng: z * Math.cos(theta), lat: z * Math.sin(theta) }
}

/** 便捷：GCJ-02 → BMapGL.Point（地图未加载时返回 null，调用方兜底） */
export const gcjPoint = (lng: number, lat: number) => {
  const BMapGL = (window as any).BMapGL
  if (!BMapGL) return null
  const bd = gcj02ToBd09(lng, lat)
  return new BMapGL.Point(bd.lng, bd.lat)
}

// ==================== marker 聚合（WEB-14 / SEP-05） ====================
//
// 背景：监控页与调度中心此前逐点 addOverlay，500 台车 = 500 Marker + 500 Label
// （Label 是有 DOM 的覆盖物，最贵），叠加 30s 全量轮询必然掉帧。
// 这里用「屏幕像素网格」分桶：同一网格内的点位合并成一个带数量的气泡，
// 且因为投影随 zoom 变化，放大后点位自然散开、无需额外的层级规则。
//
// 说明：未引入百度官方 MarkerClusterer——该库面向 BMap 2.0，对 BMapGL 支持不完整，
// 且需额外 CDN 脚本（答辩现场网络不可控）。自研网格实现零外部依赖且可单测。

/** 聚合网格边长（屏幕像素）：经验值 64px ≈ 一个气泡的可点击区域 */
export const CLUSTER_GRID_PX = 64
/** 启用聚合的点位阈值：正常运营几十台车逐点渲染可读性更好，超过才聚合 */
export const CLUSTER_MIN_POINTS = 40

export interface ClusterInput {
  lng: number
  lat: number
}

export interface ClusterBucket<T> {
  /** 网格键（调试用：`列:行`） */
  key: string
  /** 桶内点位的经纬度均值，作为聚合气泡位置 */
  lng: number
  lat: number
  items: T[]
}

/**
 * 像素网格聚合。
 * @param points 已转过坐标系（BD-09）的点集
 * @param project 把点投影为屏幕像素坐标——调用方传 `map.pointToPixel`，测试可传普通函数
 * @param gridPx 网格边长（像素）
 * @returns 分桶结果（含仅 1 个点的桶，由调用方决定渲染成普通标记还是气泡）
 */
export const clusterByGrid = <T extends ClusterInput>(
  points: T[],
  project: (p: T) => { x: number; y: number },
  gridPx = CLUSTER_GRID_PX
): ClusterBucket<T>[] => {
  const buckets = new Map<string, ClusterBucket<T>>()
  for (const point of points) {
    const px = project(point)
    const key = `${Math.floor(px.x / gridPx)}:${Math.floor(px.y / gridPx)}`
    const bucket = buckets.get(key)
    if (bucket) {
      // 增量均值，避免每个桶都再遍历一次
      const n = bucket.items.length
      bucket.lng = (bucket.lng * n + point.lng) / (n + 1)
      bucket.lat = (bucket.lat * n + point.lat) / (n + 1)
      bucket.items.push(point)
    } else {
      buckets.set(key, { key, lng: point.lng, lat: point.lat, items: [point] })
    }
  }
  return [...buckets.values()]
}

/** 是否启用聚合（点数超过阈值）。放大后由像素网格自然散开，故不再叠加 zoom 规则 */
export const shouldCluster = (pointCount: number) => pointCount > CLUSTER_MIN_POINTS

/**
 * 聚合气泡样式：数量越多颜色越深（山泉蓝三阶），白字 + 白描边保证在任意底图上可读。
 * 颜色集中定义在本文件（白名单文件），页面侧只引用函数名，便于硬编码颜色门禁通过。
 */
export const clusterBubbleStyle = (count: number) => ({
  color: '#FFFFFF',
  backgroundColor: count >= 50 ? '#123F6E' : count >= 10 ? '#1F5E9E' : '#2E7BBF',
  border: '2px solid rgba(255, 255, 255, 0.85)',
  borderRadius: '50%',
  width: '36px',
  height: '36px',
  lineHeight: '32px',
  textAlign: 'center',
  fontSize: '13px',
  fontWeight: '600',
  boxShadow: '0 2px 8px rgba(11, 18, 32, 0.35)'
})

// ==================== 大屏暗色底图（SEP-05） ====================

/**
 * 百度 GL 暗色底图样式（「山乡夜航 · 山泉蓝调」）。
 * 用法：`map.setMapStyleV2(BIGSCREEN_MAP_STYLE)`；若 AK 或 SDK 版本不支持该方法，
 * 调用方需回退到"半透明深蓝遮罩"方案（见 bigscreen/index.vue）。
 */
export const BIGSCREEN_MAP_STYLE = {
  styleJson: [
    { featureType: 'background', elementType: 'geometry', stylers: { color: '#0B1220' } },
    { featureType: 'land', elementType: 'geometry', stylers: { color: '#101A2A' } },
    { featureType: 'water', elementType: 'geometry', stylers: { color: '#0E2338' } },
    { featureType: 'water', elementType: 'labels', stylers: { visibility: 'off' } },
    { featureType: 'green', elementType: 'geometry', stylers: { color: '#12222C' } },
    { featureType: 'building', elementType: 'geometry', stylers: { color: '#16233A' } },
    { featureType: 'highway', elementType: 'geometry', stylers: { color: '#243B5C' } },
    { featureType: 'highway', elementType: 'labels', stylers: { color: '#93A5BC' } },
    { featureType: 'arterial', elementType: 'geometry', stylers: { color: '#1D2C42' } },
    { featureType: 'arterial', elementType: 'labels', stylers: { color: '#93A5BC' } },
    { featureType: 'local', elementType: 'geometry', stylers: { color: '#182742' } },
    { featureType: 'local', elementType: 'labels', stylers: { color: '#7C8CA3' } },
    { featureType: 'railway', elementType: 'geometry', stylers: { color: '#1D2C42' } },
    { featureType: 'subway', elementType: 'geometry', stylers: { color: '#1D2C42' } },
    { featureType: 'boundary', elementType: 'geometry', stylers: { color: '#23364F' } },
    { featureType: 'administrative', elementType: 'labels', stylers: { color: '#C7D6E6' } },
    { featureType: 'poi', elementType: 'labels', stylers: { color: '#93A5BC' } },
    { featureType: 'label', elementType: 'labels', stylers: { color: '#93A5BC' } },
    { featureType: 'manmade', elementType: 'geometry', stylers: { color: '#16233A' } }
  ]
}

