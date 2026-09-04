#!/usr/bin/env bash
# deploy/scripts/generate-algorithm-report.sh
# 生成算法验收报告 artifacts/algorithm-verification.json。
#
# 用法：
#   generate-algorithm-report.sh [BASE_URL] [BLACKBOX_OUTPUT_FILE]
#   默认 BASE_URL=http://127.0.0.1:18081
#   默认 BLACKBOX_OUTPUT_FILE=artifacts/blackbox-output.txt
#
# 输入：blackbox-output.txt（由前一步 algorithm-blackbox-test.sh 生成）
# 输出：artifacts/algorithm-verification.json
#
# 注意：本脚本不再自行执行 algorithm-blackbox-test.sh，
#       黑盒测试结果由调用方通过 BLACKBOX_OUTPUT_FILE 传入。

set -Eeuo pipefail

BASE_URL="${1:-http://127.0.0.1:18081}"
BLACKBOX_OUTPUT_FILE="${2:-artifacts/blackbox-output.txt}"
OUTPUT_DIR="${OUTPUT_DIR:-artifacts}"
OUTPUT_FILE="$OUTPUT_DIR/algorithm-verification.json"

mkdir -p "$OUTPUT_DIR"

# 1. 检查黑盒测试输出文件存在
if [[ ! -f "$BLACKBOX_OUTPUT_FILE" ]]; then
    echo "ERROR: blackbox output file not found: $BLACKBOX_OUTPUT_FILE" >&2
    echo "       The algorithm-blackbox-test.sh step must run before this step." >&2
    exit 1
fi

# 2. 从 $BASE_URL/health 获取 algorithmVersion
ALGO_VERSION=$(curl -sf "$BASE_URL/health" 2>/dev/null \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('algorithmVersion','unknown'))" 2>/dev/null \
    || echo "unreachable")

# 3. 根据 algorithmVersion 计算 algorithmType
if [[ "$ALGO_VERSION" == *"haco-cps"* ]]; then
    ALGO_TYPE="HACO-CPS"
elif [[ "$ALGO_VERSION" == *"ortools"* ]]; then
    ALGO_TYPE="OR-Tools Baseline"
else
    ALGO_TYPE="Unknown"
fi

# 4. 从 blackbox-output.txt 判断 PASS/FAIL
if grep -q "RESULT: PASS" "$BLACKBOX_OUTPUT_FILE"; then
    BB_STATUS="PASS"
else
    BB_STATUS="FAIL"
fi

# 5. 生成时间戳
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null \
    || python3 -c "from datetime import datetime; print(datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'))")

# 6. 使用安全的 Python JSON 生成（通过 stdin 传参，禁止 shell 变量拼接）
python3 - "$ALGO_VERSION" "$ALGO_TYPE" "$BB_STATUS" "$BASE_URL" "$TIMESTAMP" "$BLACKBOX_OUTPUT_FILE" "$OUTPUT_FILE" <<'PYEOF'
import json
import sys
from pathlib import Path

algo_version = sys.argv[1]
algo_type = sys.argv[2]
bb_status = sys.argv[3]
base_url = sys.argv[4]
timestamp = sys.argv[5]
blackbox_file = Path(sys.argv[6])
output_file = Path(sys.argv[7])

lines = blackbox_file.read_text(encoding="utf-8").splitlines()

report = {
    "algorithmVersion": algo_version,
    "algorithmType": algo_type,
    "status": bb_status,
    "blackboxTestOutput": lines,
    "timestamp": timestamp,
    "baseUrl": base_url,
}

output_file.parent.mkdir(parents=True, exist_ok=True)
output_file.write_text(
    json.dumps(report, indent=2, ensure_ascii=False) + "\n",
    encoding="utf-8",
)
PYEOF

# 7. 严格验证输出文件
if [[ ! -s "$OUTPUT_FILE" ]]; then
    echo "ERROR: report file is empty or missing: $OUTPUT_FILE" >&2
    exit 1
fi

python3 -m json.tool "$OUTPUT_FILE" >/dev/null 2>&1 || {
    echo "ERROR: report file is not valid JSON: $OUTPUT_FILE" >&2
    exit 1
}

# 8. 输出摘要
echo "Algorithm verification report written to: $OUTPUT_FILE"
echo "Status: $BB_STATUS"
echo "Algorithm Version: $ALGO_VERSION"
