#!/usr/bin/env bash
# deploy/scripts/test_generate_report.sh
# 验证 generate-algorithm-report.sh 的正确性。
#
# 用法：bash deploy/scripts/test_generate_report.sh
# 前置条件：python3 可用

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPORT_SCRIPT="$SCRIPT_DIR/generate-algorithm-report.sh"
PASS=0
FAIL=0

pass() { echo "[PASS] $1"; PASS=$((PASS + 1)); }
fail() { echo "[FAIL] $1"; FAIL=$((FAIL + 1)); }

# Windows/Git Bash compatibility: convert /tmp/ path to Windows path for Python
to_win_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        echo "$1"
    fi
}

# ─── Test 1: blackbox-output.txt 含特殊字符时 JSON 仍然合法 ───

test_special_chars() {
    local tmpdir
    tmpdir=$(mktemp -d)
    local wintmpdir
    wintmpdir=$(to_win_path "$tmpdir")

    cat > "$tmpdir/blackbox-output.txt" <<'EOF'
[PASS] 1. GET /health (HTTP 200)
[FAIL] 2. unknown station (expected 400, got 500)
       {"error":"Station 'S99' not found","detail":"it's a \"test\" with 'quotes'"}
[PASS] 3. feasible request (status=feasible)
RESULT: PASS
Special chars: backslash \ tab
Chinese: 中文测试 Japanese: 日本語 Korean: 한국어
Quotes: single ' double " backtick `
JSON inline: {"key":"value","arr":[1,2,3]}
EOF

    (cd "$tmpdir" && bash "$REPORT_SCRIPT" "http://127.0.0.1:19999" "$tmpdir/blackbox-output.txt") \
        > "$tmpdir/stdout.txt" 2>&1 || true

    local json_path="$tmpdir/artifacts/algorithm-verification.json"
    if [[ -s "$json_path" ]]; then
        if python3 -m json.tool "$(to_win_path "$json_path")" >/dev/null 2>&1; then
            local has_chinese has_special
            has_chinese=$(python3 -c "
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
print('yes' if any('中文' in line for line in d['blackboxTestOutput']) else 'no')
" "$(to_win_path "$json_path")")
            has_special=$(python3 -c "
import json, sys
d = json.load(open(sys.argv[1], encoding='utf-8'))
text = '\n'.join(d['blackboxTestOutput'])
# Check that backslash and quotes survived JSON round-trip
has_backslash = 'backslash' in text
has_quote_content = 'test' in text and 'quote' in text
print('yes' if has_backslash and has_quote_content else 'no')
" "$(to_win_path "$json_path")")
            if [[ "$has_chinese" == "yes" && "$has_special" == "yes" ]]; then
                pass "special chars: JSON valid, Chinese and special chars preserved"
            else
                fail "special chars: content not preserved (chinese=$has_chinese, special=$has_special)"
            fi
        else
            fail "special chars: JSON is invalid"
        fi
    else
        fail "special chars: report file not generated"
    fi

    rm -rf "$tmpdir"
}

# ─── Test 2: blackbox-output.txt 不存在时脚本失败 ───

test_missing_blackbox() {
    local tmpdir
    tmpdir=$(mktemp -d)

    set +e
    (cd "$tmpdir" && bash "$REPORT_SCRIPT" "http://127.0.0.1:19999" "$tmpdir/nonexistent.txt") \
        > "$tmpdir/stdout.txt" 2>&1
    local rc=$?
    set -e

    if [[ $rc -ne 0 ]]; then
        pass "missing blackbox: script failed with exit code $rc"
    else
        fail "missing blackbox: script should have failed but exited 0"
    fi

    rm -rf "$tmpdir"
}

# ─── Test 3: 正常输入生成有效 JSON ───

test_normal_output() {
    local tmpdir
    tmpdir=$(mktemp -d)

    cat > "$tmpdir/blackbox-output.txt" <<'EOF'
[PASS] 1. GET /health (HTTP 200)
[PASS] 2. GET /ready (HTTP 200)
[PASS] 3. feasible request (status=feasible)
RESULT: PASS
EOF

    (cd "$tmpdir" && bash "$REPORT_SCRIPT" "http://127.0.0.1:19999" "$tmpdir/blackbox-output.txt") \
        > "$tmpdir/stdout.txt" 2>&1 || true

    local json_path="$tmpdir/artifacts/algorithm-verification.json"
    if [[ -s "$json_path" ]]; then
        if python3 -m json.tool "$(to_win_path "$json_path")" >/dev/null 2>&1; then
            local status
            status=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['status'])" "$(to_win_path "$json_path")")
            if [[ "$status" == "PASS" ]]; then
                pass "normal output: JSON valid, status=PASS"
            else
                fail "normal output: expected status=PASS, got $status"
            fi
        else
            fail "normal output: JSON is invalid"
        fi
    else
        fail "normal output: report file not generated"
    fi

    rm -rf "$tmpdir"
}

# ─── Test 4: FAIL 状态正确识别 ───

test_fail_status() {
    local tmpdir
    tmpdir=$(mktemp -d)

    cat > "$tmpdir/blackbox-output.txt" <<'EOF'
[PASS] 1. GET /health (HTTP 200)
[FAIL] 2. feasible request (expected 200, got 500)
RESULT: FAIL
EOF

    (cd "$tmpdir" && bash "$REPORT_SCRIPT" "http://127.0.0.1:19999" "$tmpdir/blackbox-output.txt") \
        > "$tmpdir/stdout.txt" 2>&1 || true

    local json_path="$tmpdir/artifacts/algorithm-verification.json"
    if [[ -s "$json_path" ]]; then
        local status
        status=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['status'])" "$(to_win_path "$json_path")")
        if [[ "$status" == "FAIL" ]]; then
            pass "fail status: correctly identified FAIL"
        else
            fail "fail status: expected FAIL, got $status"
        fi
    else
        fail "fail status: report file not generated"
    fi

    rm -rf "$tmpdir"
}

# ─── Test 5: 空 blackbox-output.txt 不崩溃 ───

test_empty_blackbox() {
    local tmpdir
    tmpdir=$(mktemp -d)

    touch "$tmpdir/blackbox-output.txt"

    (cd "$tmpdir" && bash "$REPORT_SCRIPT" "http://127.0.0.1:19999" "$tmpdir/blackbox-output.txt") \
        > "$tmpdir/stdout.txt" 2>&1 || true

    local json_path="$tmpdir/artifacts/algorithm-verification.json"
    if [[ -s "$json_path" ]]; then
        if python3 -m json.tool "$(to_win_path "$json_path")" >/dev/null 2>&1; then
            pass "empty blackbox: JSON valid"
        else
            fail "empty blackbox: JSON is invalid"
        fi
    else
        fail "empty blackbox: report file not generated"
    fi

    rm -rf "$tmpdir"
}

# ─── Run all tests ───

echo "===== generate-algorithm-report.sh tests ====="
echo ""
test_special_chars
test_missing_blackbox
test_normal_output
test_fail_status
test_empty_blackbox

echo ""
echo "===== Summary ====="
echo "PASS: $PASS"
echo "FAIL: $FAIL"

if [[ $FAIL -gt 0 ]]; then
    echo "RESULT: FAIL"
    exit 1
fi
echo "RESULT: PASS"
