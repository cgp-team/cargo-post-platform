---
feature: real-bus-route-skeleton-ranker
status: delivered
updated: 2026-09-24
branch: master
commits: 2035666d..WORKTREE # uncommitted working-tree delivery
---

# 真实公交线骨架 + 真实站点订单训练插入排序器

## Report

**What was built** — 真实 `route=bus` 骨架 + 真实坐标订单；距离 100% 真实道路。**1-step 混合标签** + 26 维无偏斜特征。**默认 `max_iterations=8`**（实测更深目标变差，见 `ab_depth_sweep.py`），收尾精修交 ALNS。off=纯启发式，force=ML。

**Verification** — 干净 A/B（5×5，22 单 pool=64，iters=8）：mean ratio=**0.930** median=0.972，最差 1.019，零大劣化；`pytest` 37 passed（且耗时减半）。

**Journey log** — 1) 深度非单调：纯 ACO 2 层优于 8/20。2) ALNS `route.copy` 吃满墙钟，对照须钉死/关闭。3) 默认 ACO 深度定为 8。

## [S1] Problem
既有 ranker 骨架/订单非真实公交站序；OSM `route=bus` 因 Relation 解析错误始终为 0 条，无法用真实站序重训。

## [S2] Design
1. OSM Relation：`roles_sid=8` / `memids=9(delta)` / `types=10` packed；sint64 用 `_packed_svarints`。
2. 真实站序窗口（6–12 站）作 skeleton PASS；产品分布订单落在真实站点坐标池。
3. 标签：`pax→detour→dist→dur`；特征 24 维由 `insertion_features.compute_features` 训练/推理共用。
4. 产物 `insertion_ranker_osm_v2`；`ml_ranker` 优先 v2，`can_rank=top3≥0.80`，`can_prune=top3≥0.99`。
5. `use_branch_ranker=off|auto|force`。

## [S3] Out of Scope
- 10k/50k/100k/72h 自动训练、AMap 在线验证
- 生产库历史订单导入
- 大候选池 20–25 单规模化 A/B
- LightGBM 二进制 `.model` 写入

## Tasks
- [x] T1: 修复 OSM Relation packed/zigzag 解析并抽出真实公交线站序 — acceptance: routes≥1 且真实站名有序站序 (covers: S2.1)
- [x] T2: 真实线路骨架 + 真实站点产品订单重训 — acceptance: top3≥0.80 且 can_rank=True (covers: S2.2–S2.4)
- [x] T3: 验证模型加载与相关测试 — acceptance: ml_ranker 加载 v2；相关 pytest 全绿 (covers: S2.4–S2.5)
