# CHANGELOG

本服务镜像版本与参数版本变更记录（契约 Q8：默认值随镜像版本管理，CHANGELOG 记录参数变更）。

## [ortools-1.0.0] - 2026-08-23

首版。编程组自研实现，替换算法组未交付的镜像。

- 求解器：Google OR-Tools 9.15（pywrapcp 路由模型），确定性首解策略
  PARALLEL_CHEAPEST_INSERTION，无随机元启发式，同输入必然同输出。
- `algorithmVersion=ortools-1.0.0`，`parameterVersion=params-v1`。
- 参数版本 params-v1 默认参数：
  - 距离口径：GCJ-02 坐标两点欧氏直线（单位：度，×1000 取整求解，输出 3 位小数）。
  - 车辆固定成本：10^9（等效"先最少用车、再最短里程"，契约 Q7 优先单车）。
  - 求解时间护栏：5 秒（契约上限 10 秒）。
  - 车辆默认容量：passengerCapacity=5 / cargoCapacity=4（请求未传时）。
- 容量口径：双维度累计约束（批次内座位/仓位不复用），总需求超总容量 →
  `200 + status=infeasible + reasonCode=OVER_CAPACITY`；其余无解 → `TIMING_CONFLICT`。
- `algorithmConfig`（ACO 超参数）接受但不参与求解，超建议范围（ant_count>100 /
  max_iterations>500）响应带 `warnings` 不拒绝。
