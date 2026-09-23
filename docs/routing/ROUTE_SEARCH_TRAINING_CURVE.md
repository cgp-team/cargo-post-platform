# ROUTE_SEARCH_TRAINING_CURVE

数据：真实重庆 OSM + GH via 标签；OD 级切分防 spatial leakage。  
生产特征 = `CANDIDATE_FEATURES`（无 teacher_*）→ `teacher_leakage = 0`。

## 训练规模收益

| dataset | n_groups | test top-1 hit | adaptive wall_reduction (mean) | adaptive regret (mean) | 5-seed gate |
|---------|----------|----------------|-------------------------------|------------------------|-------------|
| V046 小样本（≈0.3K） | ~300 | — | 0.54–0.67 | ~0.009 | CANDIDATE |
| **1K** | 1000 | **0.109** | **0.488** | 0.001–0.007 | **5/5 PASS** |
| **5K** | 4918 | **0.172** | **0.639** | ~0.002–0.004 | **5/5 PASS** |
| **10K** | 6117* | **0.180** | **0.676** | 0.001–0.004 | **5/5 PASS** |
| 25K / 50K | 未完成 | — | — | — | 见下 |

\*并发生成目标 10K groups，站点池约束下实得 6117 groups（≈10 万 via 行）。

### 曲线解读

1. **1K → 5K：明显改善**（top-1 0.109→0.172，wall 0.488→0.639）  
2. **5K → 10K：收益变缓**（top-1 0.172→**0.180** 仅 +4.7% 相对；wall 0.639→0.676）  
   → **接近饱和**，继续堆数据的边际收益有限  
3. **25K / 50K groups：生成实测超时**  
   - 50K groups ≈ 80 万次 GH label + 数万 seed route  
   - 25K groups 在 30 min 窗口内未跑完 generate  
   - **不以未完成数据声称 50K 收益**  
4. 接线对照（40 OD）：adaptive 上 **10K wall 0.67 > 5K 0.53**（生产用 10K）；top4 两者接近

## 状态

```text
ROUTE_SEARCH_MODEL_1K_CANDIDATE
ROUTE_SEARCH_MODEL_5K_CANDIDATE
ROUTE_SEARCH_MODEL_10K_CANDIDATE
ROUTE_SEARCH_MODEL_50K_DEFERRED   # 生成成本实测不可行 + 5K→10K 已近饱和
LONG_TRAINING_DEFERRED            # 不升 100K / 不写 ACTIVE
```

生产默认：`production_ranker.py` → **branch_ranker_10k_seed3407**  
模型目录：`algorithm/data/model_registry/branch_ranker_{1k,5k,10k}_seed*`
