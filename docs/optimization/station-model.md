# 站点体系

## 现状
- `transport_station`：station_code / station_name / station_level / longitude / latitude / address / status(0启用 1停用)。
- `transport_route`：线路；`transport_route_station`：线路经停（sequence_no + planned_minutes 计划分钟）。
- 规划站点与现实站点统一为 `Station`，线路可混合规划/现实站点。

## 与任务书差距
- 无 `sourceType(PLANNED/REAL/IMPORTED)` 字段。任务书要求允许标识来源但不影响核心调度。
- **建议**：后续加 `source_type` 列（默认 PLANNED），仅用于基础数据管理/展示，不参与算法输入。

## 说明
- 公交骨架（Phase 5）用 `transport_route_station` 按 sequence_no 生成车辆必经站（`DispatchServiceImpl.resolveSkeleton`），场站自动排除（算法自行作为起终点）。
- 站点坐标缺失的经停不参与 ETA 里程累计（估算层按 0 处理），不中断链路。
