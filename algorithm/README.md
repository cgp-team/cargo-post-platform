# 客货邮路线规划算法服务（ortools-1.1.0）

编程组自研的路线规划算法服务（生产候选）。算法组镜像迟迟未交付，本服务按已对齐的接口契约
（`docs/api/algorithm-api.yaml`）自研实现，求解器使用 Google OR-Tools（pywrapcp 路由模型）。
契约未限定求解器（`docs/algorithm-doc-review.md` 差异表"求解算法：契约不限定"），因此
`algorithmVersion` 以 `ortools-` 前缀区别于算法组 ACO 方案。

## 接口

| 接口 | 说明 |
|---|---|
| `POST /api/v1/plan` | 提交规划任务，同步返回 200 + 规划结果；`requestId` 幂等保留 24h，重复提交返回首次结果 + `cached: true` |
| `GET /api/v1/result/{requestId}` | 轮询结果：200 完成 / 202 计算中 / 404 不存在或超过保留期 |
| `GET /health` | 存活检查 |
| `GET /ready` | 就绪检查 |

关键口径：

- 规模上限 413（`OVER_LIMIT`）：站点 > 30 / 订单 > 25 / 车辆 > 3。
- 参数错误 400（`INVALID_INPUT`）：未知站点引用、客运订单缺上下车站、无可用车辆等。
- 无解统一 `200 + status=infeasible + reasonCode`：总需求超总容量 → `OVER_CAPACITY`；
  其余不可行 → `TIMING_CONFLICT`；不返回部分方案，不使用 `PARTIAL_ONLY`。
- `scenario` 为 mock 专属混沌字段，本服务接受但忽略（契约标注"真实算法可忽略"）。
- `algorithmConfig`（ACO 超参数）全部接受但不参与求解，超出建议范围时响应带 `warnings` 不拒绝。
- 请求体省略的可选字段一律按"没有"处理，不报参数错误。

## 求解器（app/solver.py）

- 订单三类：PASSENGER（上车站+下车站，强制同车、先上后下）、DELIVERY（场站→站点）、PICKUP（站点→场站）。
- 容量双维度累计约束：载客维度 BOARD +1（批次内座位不复用）、载货维度 DELIVER/PICKUP +itemCount，
  单车次累计量 ≤ `passengerCapacity`（默认 5）/ `cargoCapacity`（默认 4），与 `OVER_CAPACITY`
  预检（总需求 vs 总容量）口径一致。
- 车辆池 ≤ 3，求解器自动选用子集：每启用一车计大额固定成本，目标函数等价于"先最少用车、再最短里程"（契约 Q7 优先单车）。
- 目标：总里程最小；距离提供方按 `AMAP_KEY` 配置切换（`app/distance.py`）：未配置时为两点
  欧氏直线（输入坐标 GCJ-02，单位：度）；配置后为高德驾车路网距离（真实公里）。输出保留 3 位小数，
  `distanceUnit` 字段标明单位（`"degree"` / `"km"`），高德不可用（失败/超时/配额错误）时整单
  降级回直线并在 `warnings` 标注。详见 CHANGELOG [ortools-1.1.0]。
- 确定性：仅使用确定性首解策略（PARALLEL_CHEAPEST_INSERTION），无随机元启发式，同输入必然同输出。
- 性能：满规模（25 单）求解远低于契约 10 秒时限（实测毫秒级），求解侧另设 5 秒护栏。

## 本地运行

```bash
cd algorithm
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 18081
```

## 测试

```bash
# 本服务测试（契约套件进程内自验 + 求解器单测）
cd algorithm && .venv/bin/python -m pytest tests -q

# 用 mock 仓库的契约验收套件跨进程验收本服务（先按上文起 uvicorn）
cd mock-algorithm && ALGORITHM_BASE_URL=http://127.0.0.1:18081 .venv/bin/python -m pytest tests/contract -q
```

`tests/contract/test_contract.py` 复刻自 mock-algorithm 并逐条保持一致，未设置
`ALGORITHM_BASE_URL` 时回退到本服务进程内 TestClient 自验。

## 与 mock-algorithm 的关系

- `mock-algorithm/` 保留不动：契约壳参考实现 + 混沌测试（scenario 字段）+ 适配层联调。
- `algorithm/` 为生产候选实现：同一契约、真实求解。二者接口行为一致（幂等、错误码、轮询语义），
  差异仅在求解结果（mock 为确定性伪方案，本服务为真实寻优）与版本标识。
- 上线切换：业务后端将 `ALGORITHM_BASE_URL` 从 `http://mock-algorithm:8000` 改为
  `http://algorithm:8000` 即可，适配层无需改动。

## Docker

```bash
docker build -t cargo-post/algorithm:1.0.0 algorithm/
```

基镜像 `python:3.11-slim`（契约 Q11，linux/amd64）。compose 服务见 `deploy/docker-compose.yml`
的 `algorithm` 服务（端口仅绑 127.0.0.1，默认 18081）。镜像构建在 CI container-validation
job 中验证。
