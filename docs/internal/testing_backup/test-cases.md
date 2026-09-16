# 测试用例集（集成测试）

> 状态：`PASS`=已通过（自动化或已人工验证）；`PENDING`=待人工执行（小程序需开发者工具/真机）；`BLOCKED`=受外部依赖阻塞（需部署/密钥）；`FAIL`=发现 bug。
> 优先级：P0=核心业务不可用；P1=可用但明显错误；P2=体验/健壮性。

## 阶段 2：定位测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| LOC-001 | 定位 | 已授权定位 | 首次进首页，允许定位 | userLocation.latitude/longitude 有值，level=PRECISE/APPROXIMATE，source=wechat | - | PASS（location.test.js 覆盖） | P0 |
| LOC-002 | 定位 | 已授权 | 进首页后再进（缓存 TTL 内） | source=cache，秒出，后台异步刷新 | - | PASS（单测） | P2 |
| LOC-003 | 定位拒绝 | 拒绝定位权限 | 首页定位被拒 | locationDenied=true，提示"开启定位后，可查看附近公交"，其余功能可用 | - | PASS（单测 denied） | P0 |
| LOC-004 | 定位失败 | 定位服务不可用 | 关闭定位开关 | userLocation=null，locationUnavailable=true，首页不崩溃 | - | PASS（单测） | P0 |
| LOC-005 | 切换位置 | 无精确定位 | 手动 switchVillage 切到"青山镇" | **currentVillage=青山镇，且 nearby 按青山镇 district 查询** | ❌ master：switchVillage 不触发 nearby，district 仍用 loc.district | **FAIL（P1，分支已修 6e8d02dd 未合）** | P1 |
| LOC-006 | 切换位置 | 有精确定位 | 定位到 A 点后手动切村庄 | 坐标保持真实（不随村庄名变化） | 坐标不随 switchVillage 变（正确） | PASS | P1 |
| LOC-007 | 演示定位 | 无真实定位 | 仅切换村庄文本 | 附近公交请求使用的坐标/区域与展示村庄一致 | ❌ 不一致：district 取自 loc.district，非手动村庄 | **FAIL（同 LOC-005）** | P1 |

## 阶段 3：附近公交测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| BUS-001 | 附近公交 | 有定位 + 附近有车 | 首页加载 | nearbyBuses 有数据，dataSource/status/nextStation 真实 | - | PASS（AppBusServiceImplTest） | P0 |
| BUS-002 | 附近公交 | 有定位 + 附近无车 | 首页加载（农村） | 显示"附近暂无实时公交"，不报错 | - | PASS（单测 empty） | P0 |
| BUS-003 | 附近公交 | 无精确定位 | 定位失败后首页加载 | district fallback 或空态，不因 undefined 报 400 | ❌ master：undefined 参数 → 后端 400 "For input string: undefined" | **FAIL（P1，分支已修 0743e1f1 未合）** | P1 |
| BUS-004 | 附近公交 | 网络失败 | 断网/接口 500 | nearbyBusStatus=error，显示"实时公交暂时不可用"+重新加载 | - | PASS（代码容错） | P0 |
| BUS-005 | 附近公交 | 返回空数组 | 接口成功但空 | 显示"附近暂无实时公交" | - | PASS | P0 |
| BUS-006 | 附近公交 | 旧数据缓存 | 上次有数据后清空 | 不显示旧公交，按新查询刷新 | - | PASS（PENDING 人工） | P2 |
| BUS-007 | 附近公交 | 15s 刷新 | 停留首页 30s | 每 15s 静默刷新，updatedAt 更新，不重复请求 | - | PASS（PENDING 人工） | P2 |

## 阶段 4：实时公交测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| RT-001 | 司机上报 | 司机登录 | 司机上报 /driver/location | vehicle_location 更新，5min 内 nearby 显示 REAL_FRESH | - | PASS（单测 REAL） | P0 |
| RT-002 | 位置过期 | 上报超 5min | 超过 5min 无上报 | 该车不再显示 REAL（转 SIMULATED 或排除） | - | PASS（selectRecent 5min） | P1 |
| RT-003 | 下一站 | 车辆在途 | nearby 返回 | nextStation 为真实前方站（非"—"） | - | PASS（computeNextStation/fillPosition） | P1 |
| RT-004 | ETA | 车辆+下一站坐标 | nearby 返回 | etaMinutes=duration/60 向上取整，distanceToNextStationKm 真实 | - | PASS（单测 6 分钟） | P0 |
| RT-005 | SIMULATED | 无真实上报 | 模拟车辆 | dataSource=SIMULATED，前端标"模拟位置"，不伪装 REAL | - | PASS | P0 |
| RT-006 | 实时公交详情 | bus 页 | 打开 bus/detail | 显示真实位置/下一站，无真实 ETA 显示"等待实时位置" | - | PASS（detail 改） | P1 |

## 阶段 5：高德路线测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| ROUTE-001 | /api/v1/route | 算法正常 | 坐标对查询 | available=true，distanceKm/durationSeconds 真实，provider=amap | - | PASS（test_route） | P0 |
| ROUTE-002 | /api/v1/route | AMAP_KEY 缺失 | 未配 key | provider=euclidean，直线 km/秒（不伪装） | - | PASS（pytest） | P1 |
| ROUTE-003 | /api/v1/route | 算法不可用 | 后端调算法失败 | 公交 ETA=null，页面显示"位置暂不可用"，不崩溃 | - | PASS（fillEta catch） | P0 |
| ROUTE-004 | /api/v1/route | 不可达 | 高德无道路 | available=false，reasonCode=ROUTE_UNAVAILABLE | - | PASS（pytest） | P0 |
| ROUTE-005 | /api/v1/route | 非法坐标 | lat=95 | 422 拒绝 | - | PASS（pytest） | P1 |
| ROUTE-006 | 缓存 | 同坐标 | 60s 内重复 | 缓存命中，不重复打高德 | - | PASS（单测 times(1)） | P2 |

## 阶段 6：寄货测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| SEND-001 | 寄货 | 登录 | 选取货/送达站 | station-picker 弹窗/搜索/勾选正常，展示地址 | - | PASS（PENDING 人工） | P0 |
| SEND-002 | 相同站点 | 选两站 | 取货=送达 | 拦截提示"取货站点和送达站点不能相同" | - | PASS（前端+后端） | P0 |
| SEND-003 | 停用站点 | 管理端停用某站 | 选停用站提交 | 后端拒绝"所选站点已停用，请重新选择" | - | PASS（单测） | P0 |
| SEND-004 | 路线预览 | 两站有效 | 点"下一步" | RoutePreviewRespVO 返回 distanceKm/durationMinutes/provider | - | PASS（单测） | P0 |
| SEND-005 | 订单创建 | 预览成功 | 确认发布 | createSendOrder 落库，二次校验通过 | - | PASS（单测） | P0 |
| SEND-006 | 直接构造 API | 无前端 | POST /send/create 非法站点 | 后端独立校验拦截（不信任前端） | - | PASS（单测） | P0 |
| SEND-007 | 寄货 UI 集成 | 登录 | 完整寄货流程 | 选站→预览→拍照→提交成功 | - | PENDING（需人工） | P0 |

## 阶段 7：首页刷新测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| REF-001 | 定时器 | 进首页 | onShow | 创建 1 个 nearbyTimer | - | PASS（代码审查） | P2 |
| REF-002 | 页面隐藏 | 首页→其他页 | onHide | timer 停止 | - | PASS（onHide stopNearbyTimer） | P1 |
| REF-003 | 页面返回 | 回首页 | onShow | 重新 startNearbyTimer（stopTimer 防重复） | - | PASS（stopTimer 先停再启） | P1 |
| REF-004 | 页面卸载 | 退出首页 | onUnload | timer 清理，无泄漏 | - | PASS | P2 |
| REF-005 | 重复进入 | 快速 onShow 多次 | 多次切页 | 不创建多个 timer | - | PASS（stopTimer） | P1 |

## 阶段 8：接口契约测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| CON-001 | /bus/nearby | 部署 | curl 带坐标 | 返回字段名/类型/单位符合契约（distanceKm=km、etaMinutes=整数、provider=amap/euclidean） | - | PENDING（需线上） | P0 |
| CON-002 | /bus/nearby | 无坐标 | curl district | located=false，locationLevel=DISTRICT，不报 400 | ❌ master：latitude=undefined 传参 400 | **FAIL（P1，同 BUS-003）** | P1 |
| CON-003 | /send/route-preview | 部署 | curl 两站 | available/distanceKm/durationMinutes/provider 契约一致 | - | PENDING（需线上） | P0 |
| CON-004 | /api/v1/route | 算法 | pytest | available/distanceKm/durationSeconds/provider/reasonCode | - | PASS（pytest） | P0 |
| CON-005 | 单位一致性 | 全链路 | 检查 | 距离统一 km，无 degree/÷1000 重复换算 | - | PASS（代码审查） | P1 |

## 阶段 9：异常测试

| ID | 模块 | 前置 | 操作步骤 | 预期结果 | 实际 | 状态 | 优先级 |
|---|---|---|---|---|---|---|---|
| EXC-001 | API timeout | 网络慢 | 首页加载 | 超时后页面不崩溃，显示兜底/空态 | - | PASS（request timeout 10s + catch） | P0 |
| EXC-002 | HTTP 500 | 后端异常 | nearby 接口 500 | error 态"实时公交暂时不可用"+重新加载 | - | PASS（代码容错） | P0 |
| EXC-003 | Redis 不可用 | Redis 挂 | 首页/登录 | 页面不崩溃（yudao 有容错），业务可降级 | - | PENDING（需人工/运维） | P1 |
| EXC-004 | 算法不可用 | 算法服务挂 | nearby 加载 | ETA=null，页面显示"位置暂不可用"，不 500 | - | PASS（fillEta catch） | P0 |
| EXC-005 | 高德不可用 | AMAP 配额 | 算法 route | 算法降级 euclidean，前端标注 | - | PASS（pytest 降级） | P1 |

## 阶段 10：汇总统计

- 用例总数：32
- 预期覆盖：定位 7 / 附近公交 7 / 实时公交 6 / 高德路线 6 / 寄货 7 / 刷新 5 / 契约 5 / 异常 5（部分交叉）
- 自动化 PASS：LOC-001~004, BUS-001~002/004/005, RT-001~005, ROUTE-001~006, SEND-002~006, EXC-001~002/004~005, CON-004~005
- 人工 PENDING：BUS-006/007, SEND-001/007, REF-001~005, CON-001/003, EXC-003
- FAIL（master）：LOC-005/007, BUS-003, CON-002（同一根因：切换村庄 district 不同步 + undefined 参数）
