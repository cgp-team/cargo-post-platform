# Road Route（真实道路）

## 原则
车辆实际运行必须沿**真实道路 polyline**，严禁 A→B 直线作为最终运行路线。
Java / 小程序 / 司机端均不直连高德，统一走算法服务 `AlgorithmClient → /api/v1/route`。

## 实现（Phase 6）
- `/api/v1/route`：坐标→坐标单路线，**高德驾车路径 API（/v3/direction/driving）** 返回真实道路 polyline（GCJ-02 坐标序列）。
- 失败/超时/未配 key → **euclidean 直线兜底**（仅起终点两点，`provider=euclidean` 明确标注，不伪装真实道路）。
- **RoadSegment 缓存**：算法服务进程内按 起终点坐标 key，TTL 24h，命中 0 次外部调用（禁止每车每 5s 打高德）。
- 分段（segmentDurationSeconds/segmentDistanceKm）随调度方案明细落库（Phase 1/4）。

## 消费
- 司机地图（`/driver/route`）：拼接每段 polyline → 全路线真实道路。
- 后台调度地图（`/monitoring/vehicle-plan`）：同样拼接。
- 模拟引擎（`SimulationService` 构建模拟段时取每段 polyline）。

## 已知项
- 逐段 polyline 是否混用 euclidean 兜底未在聚合层透出（聚合层 provider 恒标 amap）；如需严格区分，后续把每段 provider 透出。
