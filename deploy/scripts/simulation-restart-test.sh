#!/usr/bin/env bash
# Simulation Restart Verification Script
# 验证重启后：旧 Run → TERMINATED, SYSTEM_RECOVERY 事件, 无幽灵 SIMULATED
# 用法: bash simulation-restart-test.sh [BASE_URL] [TOKEN]

set -euo pipefail

BASE_URL="${1:-http://localhost:48080}"
TOKEN="${2:-}"
PASS=0
FAIL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { ((PASS++)); echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { ((FAIL++)); echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

api_get() {
    local path="$1"
    if [ -n "$TOKEN" ]; then
        curl -s -H "Authorization: $TOKEN" "${BASE_URL}${path}" 2>/dev/null
    else
        curl -s "${BASE_URL}${path}" 2>/dev/null
    fi
}

api_post() {
    local path="$1"
    if [ -n "$TOKEN" ]; then
        curl -s -X POST -H "Authorization: $TOKEN" "${BASE_URL}${path}" 2>/dev/null
    else
        curl -s -X POST "${BASE_URL}${path}" 2>/dev/null
    fi
}

echo "=========================================="
echo "  Simulation Restart Verification"
echo "  Target: $BASE_URL"
echo "=========================================="
echo ""

# Step 1: Start a simulation
info "Step 1: Starting simulation..."

PLANS=$(api_get "/admin-api/transport/dispatch/plan/page?pageNo=1&pageSize=10" || echo "")
PLAN_ID=$(echo "$PLANS" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")

if [ "$PLAN_ID" = "null" ] || [ -z "$PLAN_ID" ]; then
    fail "No plan found, cannot test restart"
    exit 1
fi

PLAN_DETAIL=$(api_get "/admin-api/transport/dispatch/plan/get?id=$PLAN_ID" || echo "")
VEHICLE_ID=$(echo "$PLAN_DETAIL" | jq -r '.data.items[0].vehicleId' 2>/dev/null || echo "null")

if [ "$VEHICLE_ID" = "null" ] || [ -z "$VEHICLE_ID" ]; then
    fail "No vehicle found, cannot test restart"
    exit 1
fi

# Reset any existing
api_post "/admin-api/transport/simulation/reset?vehicleId=$VEHICLE_ID" >/dev/null 2>&1 || true
sleep 1

# Start new simulation
api_post "/admin-api/transport/simulation/start?planId=$PLAN_ID&vehicleId=$VEHICLE_ID&multiplier=10" >/dev/null
sleep 2

# Verify running
RUNTIME=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
STATUS=$(echo "$RUNTIME" | jq -r '.data.status' 2>/dev/null || echo "null")

if [ "$STATUS" = "1" ]; then
    pass "Simulation is RUNNING"
else
    fail "Simulation not running: status=$STATUS"
    exit 1
fi

# Record run ID
HISTORY=$(api_get "/admin-api/transport/simulation/history?pageNo=1&pageSize=1" || echo "")
RUN_ID=$(echo "$HISTORY" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")
info "Run ID: $RUN_ID"

# ============================================================
echo ""
info "Step 2: MANUAL STEP REQUIRED"
echo ""
echo "  Please perform the following steps manually:"
echo ""
echo "  1. Stop the Spring Boot application"
echo "  2. Wait 5 seconds"
echo "  3. Start the Spring Boot application"
echo "  4. Wait for startup to complete"
echo "  5. Press ENTER to continue verification"
echo ""
read -p "  Press ENTER when backend is restarted..."
echo ""

# ============================================================
info "Step 3: Verifying post-restart state..."

# Check run status
RUN_AFTER=$(api_get "/admin-api/transport/simulation/history?pageNo=1&pageSize=1" || echo "")
RUN_STATUS=$(echo "$RUN_AFTER" | jq -r '.data.list[0].status' 2>/dev/null || echo "null")
RUN_ID_AFTER=$(echo "$RUN_AFTER" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")

if [ "$RUN_STATUS" = "4" ] || [ "$RUN_STATUS" = "3" ]; then
    pass "Run status after restart: $RUN_STATUS (TERMINATED/COMPLETED)"
elif [ "$RUN_STATUS" = "1" ] || [ "$RUN_STATUS" = "2" ]; then
    fail "Run still RUNNING/PAUSED after restart (should be TERMINATED)"
else
    info "Run status: $RUN_STATUS"
fi

# Check for SYSTEM_RECOVERY event
if [ "$RUN_ID_AFTER" != "null" ] && [ -n "$RUN_ID_AFTER" ]; then
    EVENTS=$(api_get "/admin-api/transport/simulation/events?runId=$RUN_ID_AFTER" || echo "")
    RECOVERY_COUNT=$(echo "$EVENTS" | jq '[.data[] | select(.eventType == "SYSTEM_RECOVERY")] | length' 2>/dev/null || echo "0")
    if [ "$RECOVERY_COUNT" -gt 0 ]; then
        pass "SYSTEM_RECOVERY event found"
    else
        fail "SYSTEM_RECOVERY event not found"
    fi
fi

# Check no ghost SIMULATED vehicles
info "Step 4: Checking for ghost SIMULATED vehicles..."
RUNTIME_AFTER=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
RT_DATA=$(echo "$RUNTIME_AFTER" | jq -r '.data' 2>/dev/null || echo "null")
if [ "$RT_DATA" = "null" ]; then
    pass "No ghost SIMULATED runtime"
else
    fail "Ghost runtime exists: $RT_DATA"
fi

# ============================================================
echo ""
echo "=========================================="
echo "  RESULTS: PASS=$PASS FAIL=$((PASS + FAIL - PASS))"
echo "=========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
else
    exit 0
fi
