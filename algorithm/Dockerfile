FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
# 国内 self-hosted runner 网络差，pip 走清华镜像 + 超时重试，避免 ortools 大 wheel 下载超时失败
RUN pip install --no-cache-dir --timeout 120 --retries 5 \
    -i https://pypi.tuna.tsinghua.edu.cn/simple \
    --requirement requirements.txt

COPY app ./app

EXPOSE 8000

HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
