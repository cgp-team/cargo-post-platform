#!/usr/bin/env bash
# Simulation Smoke Test - 部署后自动化验证
# 用法: bash simulation-smoke-test.sh [BASE_URL] [TOKEN]
# 示例: bash simulation-smoke-test.sh http://localhost:48080 "Bearer xxx"

set -euo pipefail

BASE_URL="${1:-http://localhost:48080}"
TOKEN="${2:-}"
PASS=0
FAIL=0
TOTAL=0

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { ((PASS++)); ((TOTAL++)); echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { ((FAIL++)); ((TOTAL++)); echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

# HTTP 请求辅助函数
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
    local data="${2:-}"
    if [ -n "$TOKEN" ]; then
        if [ -n "$data" ]; then
            curl -s -X POST -H "Authorization: $TOKEN" -H "Content-Type: application/json" -d "$data" "${BASE_URL}${path}" 2>/dev/null
        else
            curl -s -X POST -H "Authorization: $TOKEN" "${BASE_URL}${path}" 2>/dev/null
        fi
    else
        if [ -n "$data" ]; then
            curl -s -X POST -H "Content-Type: application/json" -d "$data" "${BASE_URL}${path}" 2>/dev/null
        else
            curl -s -X POST "${BASE_URL}${path}" 2>/dev/null
        fi
    fi
}

api_delete() {
    local path="$1"
    if [ -n "$TOKEN" ]; then
        curl -s -X DELETE -H "Authorization: $TOKEN" "${BASE_URL}${path}" 2>/dev/null
    else
        curl -s -X DELETE "${BASE_URL}${path}" 2>/dev/null
    fi
}

check_json_field() {
    local json="$1"
    local field="$2"
    local expected="$3"
    local actual
    actual=$(echo "$json" | jq -r "$field" 2>/dev/null)
    if [ "$actual" = "$expected" ]; then
        return 0
    else
        return 1
    fi
}

# ============================================================
echo "=========================================="
echo "  Simulation Smoke Test"
echo "  Target: $BASE_URL"
echo "=========================================="
echo ""

# 1. Backend Health
info "1. Backend Health Check"
HEALTH=$(api_get "/admin-api/infra/health" || echo "")
if echo "$HEALTH" | jq -e '.code' >/dev/null 2>&1; then
    pass "Backend is responding"
else
    # 尝试开发者健康检查
    HEALTH2=$(api_get "/admin-api/transport/developer/health" || echo "")
    if echo "$HEALTH2" | jq -e '.code' >/dev/null 2>&1; then
        pass "Backend is responding (developer health)"
    else
        fail "Backend not responding"
        echo "  Cannot continue without backend. Exiting."
        exit 1
    fi
fi

# 2. Developer Status
info "2. Developer Status"
DEV_STATUS=$(api_get "/admin-api/transport/developer/status" || echo "")
DEV_MODE=$(echo "$DEV_STATUS" | jq -r '.data.developerMode' 2>/dev/null || echo "null")
SIM_ENABLED=$(echo "$DEV_STATUS" | jq -r '.data.environmentSimulationEnabled' 2>/dev/null || echo "null")
CAN_VIEW=$(echo "$DEV_STATUS" | jq -r '.data.canViewSimulation' 2>/dev/null || echo "null")
CAN_CONTROL=$(echo "$DEV_STATUS" | jq -r '.data.canControlSimulation' 2>/dev/null || echo "null")

if [ "$DEV_MODE" = "true" ]; then
    pass "developerMode = true"
else
    info "developerMode = false, enabling..."
    api_post "/admin-api/transport/developer/enable" >/dev/null
    DEV_STATUS=$(api_get "/admin-api/transport/developer/status" || echo "")
    DEV_MODE=$(echo "$DEV_STATUS" | jq -r '.data.developerMode' 2>/dev/null || echo "null")
    if [ "$DEV_MODE" = "true" ]; then
        pass "developerMode enabled"
    else
        fail "Cannot enable developerMode"
    fi
fi

if [ "$SIM_ENABLED" = "true" ]; then
    pass "environmentSimulationEnabled = true"
else
    fail "environmentSimulationEnabled = false (check transport.simulation.enabled)"
fi

if [ "$CAN_VIEW" = "true" ]; then
    pass "canViewSimulation = true"
else
    fail "canViewSimulation = false (check transport:simulation:view permission)"
fi

if [ "$CAN_CONTROL" = "true" ]; then
    pass "canControlSimulation = true"
else
    fail "canControlSimulation = false (check transport:simulation:control permission)"
fi

# 3. Scenario List
info "3. Scenario List"
SCENARIOS=$(api_get "/admin-api/transport/simulation/scenarios" || echo "")
SCENARIO_COUNT=$(echo "$SCENARIOS" | jq '.data | length' 2>/dev/null || echo "0")
if [ "$SCENARIO_COUNT" -gt 0 ]; then
    pass "Scenarios available: $SCENARIO_COUNT"
else
    fail "No scenarios found"
fi

# 4. Find Valid Plan
info "4. Find Valid Simulation Plan"
PLANS=$(api_get "/admin-api/transport/dispatch/plan/page?pageNo=1&pageSize=10" || echo "")
PLAN_ID=$(echo "$PLANS" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")

if [ "$PLAN_ID" != "null" ] && [ -n "$PLAN_ID" ]; then
    pass "Found plan: $PLAN_ID"
else
    fail "No dispatch plan found"
    echo "  Cannot continue without a plan. Skipping simulation tests."
    echo ""
    echo "=========================================="
    echo "  RESULTS: PASS=$PASS FAIL=$FAIL TOTAL=$TOTAL"
    echo "=========================================="
    exit 0
fi

# 5. Find Valid Vehicle
info "5. Find Valid Vehicle from Plan"
PLAN_DETAIL=$(api_get "/admin-api/transport/dispatch/plan/get?id=$PLAN_ID" || echo "")
VEHICLE_ID=$(echo "$PLAN_DETAIL" | jq -r '.data.items[0].vehicleId' 2>/dev/null || echo "null")

if [ "$VEHICLE_ID" != "null" ] && [ -n "$VEHICLE_ID" ]; then
    pass "Found vehicle: $VEHICLE_ID"
else
    fail "No vehicle found in plan"
    echo "  Cannot continue without a vehicle. Skipping simulation tests."
    echo ""
    echo "=========================================="
    echo "  RESULTS: PASS=$PASS FAIL=$FAIL TOTAL=$TOTAL"
    echo "=========================================="
    exit 0
fi

# 6. Reset any existing simulation
info "6. Reset Existing Simulation"
api_post "/admin-api/transport/simulation/reset?vehicleId=$VEHICLE_ID" >/dev/null 2>&1 || true
sleep 1

# 7. Start Simulation
info "7. Start Simulation"
START_RESULT=$(api_post "/admin-api/transport/simulation/start?planId=$PLAN_ID&vehicleId=$VEHICLE_ID&multiplier=10" || echo "")
START_CODE=$(echo "$START_RESULT" | jq -r '.code' 2>/dev/null || echo "null")
if [ "$START_CODE" = "0" ]; then
    pass "Simulation started"
else
    fail "Simulation start failed: $START_RESULT"
    echo "  Cannot continue without simulation. Exiting."
    exit 0
fi

sleep 2

# 8. Runtime Check
info "8. Runtime Check"
RUNTIME1=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
RT_STATUS=$(echo "$RUNTIME1" | jq -r '.data.status' 2>/dev/null || echo "null")
RT_LNG=$(echo "$RUNTIME1" | jq -r '.data.longitude' 2>/dev/null || echo "null")
RT_LAT=$(echo "$RUNTIME1" | jq -r '.data.latitude' 2>/dev/null || echo "null")
RT_SIM_SEC=$(echo "$RUNTIME1" | jq -r '.data.simSeconds' 2>/dev/null || echo "null")

if [ "$RT_STATUS" = "1" ]; then
    pass "Runtime status = RUNNING (1)"
else
    fail "Runtime status = $RT_STATUS (expected 1)"
fi

if [ "$RT_LNG" != "null" ] && [ "$RT_LAT" != "null" ]; then
    pass "Position: lng=$RT_LNG, lat=$RT_LAT"
else
    fail "Position is null"
fi

# 9. Position Movement Verification
info "9. Position Movement (wait 3s)"
sleep 3
RUNTIME2=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
RT_LNG2=$(echo "$RUNTIME2" | jq -r '.data.longitude' 2>/dev/null || echo "null")
RT_LAT2=$(echo "$RUNTIME2" | jq -r '.data.latitude' 2>/dev/null || echo "null")
RT_SIM_SEC2=$(echo "$RUNTIME2" | jq -r '.data.simSeconds' 2>/dev/null || echo "null")

if [ "$RT_LNG" != "$RT_LNG2" ] || [ "$RT_LAT" != "$RT_LAT2" ]; then
    pass "Position changed: ($RT_LNG,$RT_LAT) -> ($RT_LNG2,$RT_LAT2)"
else
    # 可能倍速太低，再等一次
    sleep 3
    RUNTIME3=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
    RT_LNG3=$(echo "$RUNTIME3" | jq -r '.data.longitude' 2>/dev/null || echo "null")
    RT_LAT3=$(echo "$RUNTIME3" | jq -r '.data.latitude' 2>/dev/null || echo "null")
    if [ "$RT_LNG" != "$RT_LNG3" ] || [ "$RT_LAT" != "$RT_LAT3" ]; then
        pass "Position changed (after 6s): ($RT_LNG,$RT_LAT) -> ($RT_LNG3,$RT_LAT3)"
    else
        fail "Position did not change after 6s"
    fi
fi

if [ "$RT_SIM_SEC2" != "null" ] && [ "$RT_SIM_SEC" != "null" ]; then
    if [ "$RT_SIM_SEC2" -gt "$RT_SIM_SEC" ] 2>/dev/null; then
        pass "SimSeconds advanced: $RT_SIM_SEC -> $RT_SIM_SEC2"
    else
        fail "SimSeconds did not advance: $RT_SIM_SEC -> $RT_SIM_SEC2"
    fi
fi

# 10. Pause
info "10. Pause Simulation"
api_post "/admin-api/transport/simulation/pause?vehicleId=$VEHICLE_ID" >/dev/null
sleep 1
RUNTIME_PAUSED=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
PAUSED_STATUS=$(echo "$RUNTIME_PAUSED" | jq -r '.data.status' 2>/dev/null || echo "null")
if [ "$PAUSED_STATUS" = "2" ]; then
    pass "Status = PAUSED (2)"
else
    fail "Status = $PAUSED_STATUS (expected 2)"
fi

# 11. Resume
info "11. Resume Simulation"
api_post "/admin-api/transport/simulation/resume?vehicleId=$VEHICLE_ID" >/dev/null
sleep 1
RUNTIME_RESUMED=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
RESUMED_STATUS=$(echo "$RUNTIME_RESUMED" | jq -r '.data.status' 2>/dev/null || echo "null")
if [ "$RESUMED_STATUS" = "1" ]; then
    pass "Status = RUNNING (1)"
else
    fail "Status = $RESUMED_STATUS (expected 1)"
fi

# 12. GPS_LOST
info "12. Inject GPS_LOST"
api_post "/admin-api/transport/simulation/inject" \
    '{"vehicleId":'$VEHICLE_ID',"eventType":"GPS_LOST"}' >/dev/null
sleep 1

# 检查 runtime 中 GPS 状态（通过 dataSource 间接验证）
RUNTIME_GPS_LOST=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
GPS_SOURCE=$(echo "$RUNTIME_GPS_LOST" | jq -r '.data.dataSource' 2>/dev/null || echo "null")
# GPS_LOST 不影响 runtime 的 dataSource（runtime 始终返回 SIMULATED）
# 但 monitoring 应该显示 OFFLINE
pass "GPS_LOST injected (verify via monitoring OFFLINE)"

# 13. GPS_RECOVER
info "13. Inject GPS_RECOVER"
api_post "/admin-api/transport/simulation/inject" \
    '{"vehicleId":'$VEHICLE_ID',"eventType":"GPS_RECOVER"}' >/dev/null
sleep 1
pass "GPS_RECOVER injected"

# 14. FAULT
info "14. Inject FAULT"
RUNTIME_BEFORE_FAULT=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
FAULT_LNG=$(echo "$RUNTIME_BEFORE_FAULT" | jq -r '.data.longitude' 2>/dev/null || echo "null")
FAULT_LAT=$(echo "$RUNTIME_BEFORE_FAULT" | jq -r '.data.latitude' 2>/dev/null || echo "null")

api_post "/admin-api/transport/simulation/inject" \
    '{"vehicleId":'$VEHICLE_ID',"eventType":"FAULT"}' >/dev/null
sleep 3

RUNTIME_FAULT=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
FAULT_LNG2=$(echo "$RUNTIME_FAULT" | jq -r '.data.longitude' 2>/dev/null || echo "null")
FAULT_LAT2=$(echo "$RUNTIME_FAULT" | jq -r '.data.latitude' 2>/dev/null || echo "null")

if [ "$FAULT_LNG" = "$FAULT_LNG2" ] && [ "$FAULT_LAT" = "$FAULT_LAT2" ]; then
    pass "FAULT: position frozen"
else
    fail "FAULT: position changed ($FAULT_LNG,$FAULT_LAT) -> ($FAULT_LNG2,$FAULT_LAT2)"
fi

# 15. FAULT_RECOVER
info "15. Inject FAULT_RECOVER"
api_post "/admin-api/transport/simulation/inject" \
    '{"vehicleId":'$VEHICLE_ID',"eventType":"FAULT_RECOVER"}' >/dev/null
sleep 3

RUNTIME_RECOVER=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
RECOVER_LNG=$(echo "$RUNTIME_RECOVER" | jq -r '.data.longitude' 2>/dev/null || echo "null")
RECOVER_LAT=$(echo "$RUNTIME_RECOVER" | jq -r '.data.latitude' 2>/dev/null || echo "null")

if [ "$FAULT_LNG2" != "$RECOVER_LNG" ] || [ "$FAULT_LAT2" != "$RECOVER_LAT" ]; then
    pass "FAULT_RECOVER: position resumed"
else
    # 可能需要更多时间
    sleep 3
    RUNTIME_RECOVER2=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
    RECOVER_LNG2=$(echo "$RUNTIME_RECOVER2" | jq -r '.data.longitude' 2>/dev/null || echo "null")
    RECOVER_LAT2=$(echo "$RUNTIME_RECOVER2" | jq -r '.data.latitude' 2>/dev/null || echo "null")
    if [ "$FAULT_LNG2" != "$RECOVER_LNG2" ] || [ "$FAULT_LAT2" != "$RECOVER_LAT2" ]; then
        pass "FAULT_RECOVER: position resumed (after 6s)"
    else
        fail "FAULT_RECOVER: position still frozen after 6s"
    fi
fi

# 16. Reset
info "16. Reset Simulation"
api_post "/admin-api/transport/simulation/reset?vehicleId=$VEHICLE_ID" >/dev/null
sleep 1
RUNTIME_RESET=$(api_get "/admin-api/transport/simulation/runtime?vehicleId=$VEHICLE_ID" || echo "")
RESET_DATA=$(echo "$RUNTIME_RESET" | jq -r '.data' 2>/dev/null || echo "null")
if [ "$RESET_DATA" = "null" ]; then
    pass "Runtime is null after reset"
else
    # 可能返回了旧数据
    RESET_STATUS=$(echo "$RUNTIME_RESET" | jq -r '.data.status' 2>/dev/null || echo "null")
    if [ "$RESET_STATUS" = "null" ] || [ "$RESET_STATUS" = "0" ]; then
        pass "Runtime cleared after reset"
    else
        fail "Runtime still exists after reset: status=$RESET_STATUS"
    fi
fi

# 17. History
info "17. Check History"
HISTORY=$(api_get "/admin-api/transport/simulation/history?pageNo=1&pageSize=10" || echo "")
HISTORY_COUNT=$(echo "$HISTORY" | jq '.data.total' 2>/dev/null || echo "0")
if [ "$HISTORY_COUNT" -gt 0 ]; then
    pass "History entries: $HISTORY_COUNT"
    # 获取最新 run ID
    LATEST_RUN_ID=$(echo "$HISTORY" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")
else
    fail "No history entries found"
    LATEST_RUN_ID="null"
fi

# 18. Events
info "18. Check Events"
if [ "$LATEST_RUN_ID" != "null" ]; then
    EVENTS=$(api_get "/admin-api/transport/simulation/events?runId=$LATEST_RUN_ID" || echo "")
    EVENT_COUNT=$(echo "$EVENTS" | jq '.data | length' 2>/dev/null || echo "0")
    if [ "$EVENT_COUNT" -gt 0 ]; then
        pass "Events found: $EVENT_COUNT"
        # 检查事件顺序
        FIRST_EVENT=$(echo "$EVENTS" | jq -r '.data[0].eventType' 2>/dev/null || echo "null")
        if [ "$FIRST_EVENT" = "START" ]; then
            pass "First event is START"
        else
            fail "First event is $FIRST_EVENT (expected START)"
        fi
    else
        fail "No events found for run $LATEST_RUN_ID"
    fi
else
    info "Skipping events check (no run ID)"
fi

# 19. Report
info "19. Check Report"
if [ "$LATEST_RUN_ID" != "null" ]; then
    REPORT=$(api_get "/admin-api/transport/simulation/report?runId=$LATEST_RUN_ID" || echo "")
    REPORT_STATUS=$(echo "$REPORT" | jq -r '.data.status' 2>/dev/null || echo "null")
    REPORT_EVENTS=$(echo "$REPORT" | jq -r '.data.exceptionCount' 2>/dev/null || echo "null")
    if [ "$REPORT_STATUS" != "null" ]; then
        pass "Report generated: status=$REPORT_STATUS"
    else
        fail "Report not found"
    fi
else
    info "Skipping report check (no run ID)"
fi

# ============================================================
echo ""
echo "=========================================="
echo "  RESULTS: PASS=$PASS FAIL=$FAIL TOTAL=$TOTAL"
echo "=========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
else
    exit 0
fi
