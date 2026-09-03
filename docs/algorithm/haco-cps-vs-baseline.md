# HACO-CPS vs OR-Tools Baseline 对比

## 为什么不是简单 ACO

传统 ACO 的问题：
1. **不感知业务约束**：简单的 η = 1/distance 不考虑乘客影响、货物绕行、骨架顺序
2. **不处理 Task Block**：乘客上下车、货运揽送是不可拆分的块，普通 ACO 把每个节点独立处理
3. **不理解 Skeleton Backbone**：骨架是固定主线路，任务应插入间隙而非随意打乱
4. **缺乏可行性保证**：纯 ACO 可能生成不可行解，需要额外修复

HACO-CPS 的创新：
1. **Task Block 编码**：乘客/货运作为不可拆分的任务块
2. **Skeleton Gap 插入**：在骨架间隙中搜索最佳插入位置
3. **业务感知启发式**：综合距离、乘客影响、绕行、时间风险、骨架偏离
4. **分层目标**：先可行性，再车辆数，再乘客影响，再距离

## 为什么保留 OR-Tools

1. **初始解质量**：OR-Tools 的 PATH_CHEAPEST_ARC 能快速生成高质量初始解
2. **约束满足**：OR-Tools 的约束传播能保证所有约束被正确满足
3. **格式兼容**：OR-Tools 输出的 PlanResult 格式与 Java Validator 完全兼容
4. **生产稳定**：作为 fallback，HACO 异常时能恢复到可靠的 baseline

## 自研部分在哪里

### 完全自研

1. **Task Block 编码** (`haco/encoding.py`)：将业务订单编码为不可拆分的任务块
2. **业务启发式** (`haco/heuristic.py`)：综合多维业务指标的启发式函数
3. **信息素模型** (`haco/pheromone.py`)：MMAS 风格信息素矩阵，支持精英强化
4. **蚂蚁构建** (`haco/construction.py`)：基于信息素和启发式的解构建过程
5. **局部搜索** (`haco/local_search.py`)：Relocate / Swap 算子
6. **LNS Destroy-Repair** (`haco/destroy_repair.py`)：大邻域搜索
7. **分层目标评估** (`haco/evaluator.py`)：不使用权重混合的分层比较
8. **统一调度入口** (`solver.py`)：根据 algorithmMode 调度 HACO 或 Baseline

### 基于 OR-Tools

1. **初始解生成**：使用 OR-Tools 的 PATH_CHEAPEST_ARC
2. **约束验证**：使用 OR-Tools 的约束传播
3. **Fallback**：HACO 失败时回退到 OR-Tools

### 混合

1. **扰动策略**：使用信息素引导的随机扰动，然后用 OR-Tools 重新求解
2. **解验证**：HACO 生成的候选解用 OR-Tools 验证可行性

## 算法流程对比

### OR-Tools Baseline

```
请求 → 构建路由模型 → PATH_CHEAPEST_ARC → 输出
```

- 确定性：同输入同输出
- 无随机性
- 无信息素/启发式
- 无局部搜索

### HACO-CPS

```
请求 → 任务编码 → OR-Tools 初始解 → HACO 迭代搜索 → 输出
                    │                    │
                    ▼                    ▼
                初始可行解         信息素引导扰动
                                       │
                                       ▼
                                  OR-Tools 求解
                                       │
                                       ▼
                                  局部搜索改进
                                       │
                                       ▼
                                  LNS Destroy-Repair
                                       │
                                       ▼
                                  分层目标比较
                                       │
                                       ▼
                                  更新最佳解
```

- 随机性：受 randomSeed 控制
- 信息素引导：好的解路径获得更多信息素
- 多维启发式：综合距离、乘客影响、绕行等
- 局部搜索：精细改进
- LNS：跳出局部最优

## 版本演进

| 版本 | 算法 | 特点 |
|------|------|------|
| ortools-1.0.0 | OR-Tools PATH_CHEAPEST_ARC | 初始版本 |
| ortools-1.1.0 | + 骨架约束 | 支持公交骨架 |
| ortools-1.2.0 | + 绕行阈值 | 有限绕行改派 |
| ortools-1.3.0 | + 初始载荷 | 支持 initialPassengerLoad/CargoLoad |
| haco-cps-1.0.0 | HACO-CPS | 混合蚁群优化 + 业务启发式 + 局部搜索 + LNS |

## 性能预期

在大多数场景下：
- HACO ≥ Baseline（车辆数相同或更少，距离相同或更短）
- HACO 在乘客影响和货物绕行方面有明显优势
- HACO 运行时间略长（4s vs 1s），但在 5s 限制内

在某些简单场景下：
- HACO = Baseline（OR-Tools 已经找到最优解）

在极少数场景下：
- HACO < Baseline（元启发式的随机性可能导致退化）
- 此时通过 fallback 机制保证不会比 baseline 差
