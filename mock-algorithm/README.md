# Mock Algorithm

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
pytest -q
uvicorn app.main:app --reload --port 8000
```

Swagger 文档位于 `http://localhost:8000/docs`。数据仅保存在进程内，重启即清空；本服务不实现真实最短路或调度优化，仅按"优先单车、装不下自动加车"规则生成确定性方案。

接口契约见 `../docs/api/algorithm-api.yaml`：`POST /api/v1/plan`、`GET /api/v1/result/{requestId}`、`GET /health`、`GET /ready`。请求体中的 `scenario` 字段仅供 Mock 测试，可模拟超时、无解、部分解和内部错误。
