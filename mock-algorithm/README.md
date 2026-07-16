# Mock Algorithm

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
pytest -q
uvicorn app.main:app --reload --port 8000
```

Swagger 文档位于 `http://localhost:8000/docs`。数据仅保存在进程内，重启即清空；本服务不实现真实最短路或调度优化。
