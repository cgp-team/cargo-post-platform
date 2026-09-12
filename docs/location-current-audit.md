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

## 六、后续修订（2026-09-11）：把"定位不准"变成可纠正、可解释

**现象**：人在重庆邮电大学（南岸区，106.5765/29.5325），首页/公交页显示"渝中区"。

**排查结论**：坐标链路本身没问题 —— 用同一坐标（106.5765,29.5325）实测高德 regeo 返回 `district=南岸区`、
BigDataCloud 也返回 `南岸区`；项目内只有 `utils/location.js` 调 `wx.getLocation({type:'gcj02'})`（高德 SDK 被显式传坐标，不会自行定位）。
所以"渝中区"来自**设备/系统给的粗略位置**：手机未开"精确位置"、室内无 GPS 时系统返回 WiFi/基站定位，误差可达 1~3 公里，
反查出来的区县自然落到隔壁区。开发者工具里手动设过"模拟位置"也会一直返回同一坐标。

**本次改动**：

1. **精度显式化 + 告警**：新增 `isCoarseAccuracy`(>500m) / `isVeryCoarseAccuracy`(>1000m) / `accuracyText`(35m / 3.2km)；
   首页、实时公交页、寄货页在粗定位时提示"定位精度较低（约 X），区域名可能不准"，日志加 `[AMAP_LOCATION] 定位精度较低…`。
2. **手动选点纠正（wx.chooseLocation）**：`location.chooseLocation()` 统一返回 `{source: MANUAL, manual: true, name/address/district/city}`，
   有效期 2 小时内**优先于自动定位**（用户纠正过的位置不会再被下一次粗定位覆盖），"重新定位"会放弃它。
   入口：首页定位提示条「手动选择位置」、实时公交定位条「手动选位置」、寄货页「手动选位置」（选完自动重跑可达性评估）。
3. **近期高精度结果沿用**：本次误差 >1km 且 2 分钟内有过 ≤100m 的高精度结果时，沿用高精度结果并标注
   `stale + note=当前定位精度较低，已沿用上一次高精度定位`（避免"上一分钟还准，这一分钟被粗定位覆盖"）。
4. **修一个真实字段 bug**：高德 `addressComponent` 缺字段时返回**空数组** `[]`（如 `"city":[]`），JS 里 `[]` 是 truthy，
   原 `comp.city || comp.province` 会把 `city` 写成空数组（页面显示空白）。现统一 `pickText()` 归一为字符串。

**演示前自检**（3 秒）：小程序里看到"定位精度较低"就直接点「手动选位置」在地图上点校园，一次即可纠正；
手机上还应检查「设置 → 隐私 → 定位服务 → 微信」是否开启了**精确位置**；开发者工具则检查调试器的"位置模拟"是否为真实位置。
