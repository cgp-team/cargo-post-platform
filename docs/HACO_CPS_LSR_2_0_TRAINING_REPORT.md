# HACO_CPS_LSR_2_0_TRAINING_REPORT

## P0/P1 实验失真修复

| 问题 | 修复 |
|------|------|
| synthetic `road = straight * random` | **废弃**；`RealRoadRouter` 调 OSM RoadGraph / LocalRoutingEngine |
| Reducer 接收 teacher_top_id / feasible_ids | **移除**；production 只收 protected_ids（业务规则） |
| Reduction Recall 与 Ranking 混算 | 分离；Reduction Recall **仅 offline** |
| Recall@K 在 k≥group_size 失真 | **动态 K**，k≥size → `null` |
| Best Feasible 无可行解算 1.0 | **排除**该 group |
| 单 group reduction 当总体 | 全部 test groups；weighted/mean/median/p90 |
| reducer 全局改 target_ratio | **per-call**，不污染后续 group |
| hard neg 未分型 | **model_disagreement** / **boundary** 分开 |
| group split 顺序偏差 | **hash shuffle** |
| checkpoint dataset_size=0 | 完整字段 + 过期 deadline 自动续期 |

---

## 1000 REAL Smoke（OSM + LocalRoutingEngine）— 真实结果

| 指标 | 实测值 |
|------|--------|
| samples | **1000**（quality ok=1000, bad=0） |
| groups | **35** |
| avg / min / max candidates/group | **28.57 / 20 / 37** |
| router | routed=1035, **formal=1035**, uncertain=0, cache_hit=588 |
| Ranking Recall@1/3/5/10/20 | **1.000** |
| Ranking Recall@50 | **null（N/A）** |
| Best Candidate Recall@1/3/5/10/20 | **1.000** |
| Best Feasible Recall@1/3/5/10/20 | **1.000** |
| Overall Feasible Recall | 1.0（monitoring） |
| NDCG@1/3/5/10/20 | 1.000 |
| test groups | 6 |
| weighted reduction | 0.0（保护触发，未删候选） |
| Reduction Recall@1/3/5/10 | 1.0 |
| model_disagreement_hard_negatives | **491** |
| boundary_hard_negatives | **815** |
| const_features | 35 / ~52 |
| synthetic road cost | **无** |
| MultiLeg real-road | formal 仅 LOCAL_ROAD |
| Teacher leakage | **无** |

注：6 个 test group 上 Recall=1.0 偏乐观，**不能**据此宣称系统收益。

## 测试

`test_lsr_p0_fixes.py`：**12 passed**

## run_auto_training

已实现 **1k→10k→50k→100k** + 72h deadline + 完整 checkpoint。  
**本次按指令只跑 1000 REAL smoke 后停止。**

| 项 | 状态 |
|----|------|
| 10k/50k/100k/72h | **NOT RUN**（待命） |
| 72h 可正式启动 | **YES**（`.\scripts\run_auto_training.ps1`） |
| Baseline vs ML Objective / runtime | **NOT VERIFIED** |
| model size / inference latency | **NOT VERIFIED** |
| AMap 真实验证 | **NOT VERIFIED** |
