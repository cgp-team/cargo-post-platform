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
