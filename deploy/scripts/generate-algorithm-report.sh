#!/usr/bin/env bash
# deploy/scripts/generate-algorithm-report.sh
# 生成算法验收报告 artifacts/algorithm-verification.json。
#
# 用法：
#   generate-algorithm-report.sh [BASE_URL]
#   默认 BASE_URL=http://127.0.0.1:18081
#
# 输出：artifacts/algorithm-verification.json

set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:18081}"
OUTPUT_DIR="${OUTPUT_DIR:-artifacts}"
OUTPUT_FILE="$OUTPUT_DIR/algorithm-verification.json"

mkdir -p "$OUTPUT_DIR"

# 获取算法版本
ALGO_VERSION=$(curl -sf "$BASE_URL/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('algorithmVersion','unknown'))" 2>/dev/null || echo "unreachable")

# 检测算法类型
if [[ "$ALGO_VERSION" == *"haco-cps"* ]]; then
    ALGO_TYPE="HACO-CPS"
elif [[ "$ALGO_VERSION" == *"ortools"* ]]; then
    ALGO_TYPE="OR-Tools Baseline"
else
    ALGO_TYPE="Unknown"
fi

# 运行黑盒测试，捕获输出
BB_OUTPUT=""
BB_STATUS="UNKNOWN"
if [[ -f "$(dirname "$0")/algorithm-blackbox-test.sh" ]]; then
    BB_OUTPUT=$(bash "$(dirname "$0")/algorithm-blackbox-test.sh" "$BASE_URL" 2>&1 || true)
    if echo "$BB_OUTPUT" | grep -q "RESULT: PASS"; then
        BB_STATUS="PASS"
    else
        BB_STATUS="FAIL"
    fi
fi

# 生成报告
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || python3 -c "from datetime import datetime; print(datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ'))")

python3 -c "
import json
report = {
    'algorithmVersion': '$ALGO_VERSION',
    'algorithmType': '$ALGO_TYPE',
    'status': '$BB_STATUS',
    'blackboxTestOutput': '''$BB_OUTPUT'''.strip().split('\n') if '''$BB_OUTPUT'''.strip() else [],
    'timestamp': '$TIMESTAMP',
    'baseUrl': '$BASE_URL'
}
print(json.dumps(report, indent=2, ensure_ascii=False))
" > "$OUTPUT_FILE"

echo "Algorithm verification report written to: $OUTPUT_FILE"
echo "Status: $BB_STATUS"
echo "Algorithm Version: $ALGO_VERSION"
