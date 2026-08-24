# 小程序定位链路现状审计（第一阶段）

> 目标：为"统一小程序用户定位能力"建立基线。本阶段只做定位基础设施，不碰公交/高德/算法/监控/寄货。

## 一、现状结论（对方案 10 问的逐条回答）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 当前用户经纬度从哪里得到 | `wx.getLocation`，仅首页 `pages/index/index.js:158`（天气逻辑内）+ 司机端 `pages/driver/workbench/workbench.js:89/239`（位置上报） |
| 2 | `wx.getLocation` 是否已调用 | 是（首页 + 司机工作台），但没有统一封装 |
| 3 | 当前 type 是什么 | `wgs84` |
| 4 | 是否开启 isHighAccuracy | 是（首页）：`isHighAccuracy: true, highAccuracyExpireTime: 6000` |
| 5 | 定位结果是否只用于天气 | **是**：坐标只喂 `weatherApi.fetchWeather` + `reverseGeocode`（逆地理地名），用完即弃，**无 userLocation 坐标留存** |
| 6 | "当前村庄/区域"文字来源 | ① `reverseGeocode`（腾讯LBS空Key→BigDataCloud）返回的 placeName 覆盖 `currentVillage`；② 手动 `switchVillage`（本地 `VILLAGES` 列表） |
| 7 | 免费逆地理服务 | **BigDataCloud**（免Key，`weather.js`）；腾讯位置服务 `TENCENT_KEY` 为空，未启用 |
| 8 | 定位失败 fallback | 首页保留本地兜底天气 `localMockWeather()`；村庄名不更新（维持默认'云山村'或上次手动选择） |
| 9 | 有没有缓存用户位置 | **无坐标缓存**；只有 `weatherCache`（天气数据，TTL 10min） |
| 10 | 有没有统一 location service | **没有**：定位逻辑埋在 `index.loadWeather`，司机端另有一套 |

## 二、当前调用链（首页）

```
index.onLoad/onShow
  └─ loadWeather()
       ├─ weatherCache TTL 命中 → 直接渲染兜底，返回（跳过定位）
       ├─ wx.getSetting → scope.userLocation === false → 保留兜底，返回
       ├─ wx.getLocation({ type:'wgs84', isHighAccuracy:true, highAccuracyExpireTime:6000 })
       │    ├─ success(loc) → _fetchWeather(lat, lon)
       │    │     ├─ weatherApi.reverseGeocode(lat,lon) → placeName → currentVillage + globalData.currentVillage
       │    │     └─ weatherApi.fetchWeather(lat,lon) → 天气卡
       │    └─ fail → 保留兜底天气，return
       └─ (switchVillage 手动切换 VILLAGES 覆盖 currentVillage)
```

## 三、关键问题（本阶段要修的）

1. **坐标用完即弃**：`_fetchWeather(lat, lon)` 拿到坐标后只用于天气请求和逆地理，**没有保存 latitude/longitude/accuracy/timestamp**。后续"附近实时公交"无坐标可用。
2. **区域名 ≠ 定位**：`currentVillage` 是展示文本（逆地理地名 / 手动切换），内部没有真实坐标。不能让展示文本替代定位结果。
3. **定位逻辑与天气耦合**：定位在 `loadWeather` 里，天气缓存命中时**完全跳过定位**（首页 never 定位）；换村庄用本地列表，与真实定位无关。
4. **无缓存、无精度分级、无统一 location service**：需要 `miniprogram/utils/location.js` 统一：原生高精度定位 → 逆地理 fallback → 缓存 → `locationLevel(PRECISE/APPROXIMATE/DISTRICT/UNKNOWN)`。
5. **权限拒绝**：首页仅静默保留兜底，无"去设置"引导。

## 四、司机端定位（不在本阶段范围）

`pages/driver/workbench/workbench.js` 有独立的 `wx.getLocation`（司机位置上报）。本阶段不动，但统一 LocationService 建立后可作为后续迁移点（先记录不改）。

## 五、涉及文件清单（本阶段将改动/新增）

- 新增：`miniprogram/utils/location.js`（统一 LocationService）
- 修改：`miniprogram/pages/index/index.js`（首页接入，不再直接管定位细节）
- 可能微调：`miniprogram/app.js`（globalData 增加 userLocation 字段）
- 新增测试：`miniprogram/tests/location.test.js`（如项目有测试约定）
- 不动：`weather.js`（reverseGeocode 保留，LocationService 复用其能力）、司机端、公交、高德、算法、寄货
