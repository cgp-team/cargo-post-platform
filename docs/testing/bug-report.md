# Bug 报告（master @ b8031f44 审计）

> 状态说明：下列 P1 在分支 `feat/dispatch-algorithm-integration` 已修复但**未合入 master**；本报告记录 master 现存问题及修复方案。

## BUG-001 [P1] 附近公交请求传 `undefined` 坐标参数 → 后端 400

- **模块**：小程序 `miniprogram/utils/api.js` `getNearbyRealtimeBuses`
- **现象**：无精确定位时（农村/定位失败），`loadNearbyBusData` 传 `latitude/longitude/radius = undefined`，微信 `wx.request` 将 `undefined` 序列化为字符串 `"undefined"`，后端 `Method parameter 'latitude': Failed to convert ... 'java.lang.Double'; For input string: "undefined"` → 400。
- **影响**：无定位用户附近公交功能报错（首页 console 报错，公交区不加载）。P0 边界（定位失败常见）。
- **根因**：请求参数未过滤 undefined。
- **修复**（分支 `0743e1f1`）：`getNearbyRealtimeBuses` 只组装非空参数（latitude/longitude/radius/district 均有值才传）。
- **验证**：修复后无定位 → district fallback / 空态，不再 400。

## BUG-002 [P1] 手动切换村庄后附近公交不刷新、district 不随切换更新

- **模块**：小程序 `miniprogram/pages/index/index.js` `switchVillage`
- **现象**：用户手动切到"青山镇"，`currentVillage` 文本变了，但 `loadNearbyBusData` 未触发；且 district fallback 取自定位逆地理 `loc.district`，**不是手动选择的村庄** → 附近公交仍按旧条件/空。
- **影响**：切换演示位置后看不到该区域公交（演示定位功能失效）。
- **根因**：`switchVillage` 只改文本 + `loadHomeData`，不触发 nearby；`district` 来源未包含手动选择。
- **修复**（分支 `6e8d02dd`）：`switchVillage` 标记 `villageManual` + 触发 `loadNearbyBusData`；`loadNearbyBusData` 的 district 优先用手动选择的 `currentVillage`，否则用定位 `loc.district`。
- **验证**：切青山镇 → nearby 按"青山镇"区域 fallback 查询（显示该线路车辆或空态，取决于数据）。

## 已确认非 Bug（设计/降级，非错误）

- 附近无公交显示"附近暂无实时公交"（农村场景，非 bug，是数据空态）。
- 算法/高德不可用时公交 ETA 不显示、页面正常（容错，符合"不伪装"）。
- SIMULATED 车辆前端标"模拟位置"（不伪装 REAL）。

## 待观察 / 需人工确认

- **bus/detail 无真实 ETA 数据源**（复用 /bus/lines 估算字段，已改不显示假 ETA，显示"等待实时位置"）——架构 gap，P2。
- **Redis 不可用**场景端到端行为未验证（需运维配合，PENDING）。
- **线上接口契约**（nearby/route-preview 真实返回）需部署后 curl 回归（CON-001/003，PENDING）。

## 修复状态

| Bug | 优先级 | master 状态 | 分支修复 commit | 合入状态 |
|---|---|---|---|---|
| BUG-001 undefined 参数 | P1 | 存在 | `0743e1f1` | 未合 master |
| BUG-002 村庄切换 | P1 | 存在 | `6e8d02dd` | 未合 master |
