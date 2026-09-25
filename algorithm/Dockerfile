FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
# pip 源做成构建参数：国内 self-hosted runner（deploy-dev）走默认清华镜像；
# GitHub 托管 runner（ci container-validation）在海外，经 compose args / 环境变量切官方 PyPI，避免跨海拉镜像超时
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
# 主源失败则回退官方 PyPI（self-hosted 拉镜像源偶发超时）
RUN pip install --no-cache-dir --timeout 120 --retries 5 \
    -i "${PIP_INDEX_URL}" \
    --requirement requirements.txt \
    || pip install --no-cache-dir --timeout 120 --retries 5 \
    --index-url https://pypi.org/simple \
    --requirement requirements.txt

COPY app ./app

# 构建期导入自检：启动失败提前暴露，避免 deploy 阶段 60s 健康检查才报红
RUN python -c "import app.main; import app.haco.ml_ranker; import app.haco.insertion_features; print('algorithm import ok')"

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"

# DEP-04：保持单 worker（ortools 求解为 CPU 密集，多 worker 会互相挤占 2 核限额；
# FastAPI 同步端点由线程池排队，后端侧已有 15s 超时+冷却快速失败，构成背压）
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
