#!/usr/bin/env bash
# Simulation Performance Smoke Test
# 用法: bash simulation-performance-smoke.sh [BASE_URL] [TOKEN]

set -euo pipefail

BASE_URL="${1:-http://localhost:48080}"
TOKEN="${2:-}"
DURATION=60

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
result() { echo -e "${GREEN}[RESULT]${NC} $1"; }

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
echo "  Simulation Performance Smoke Test"
echo "  Target: $BASE_URL"
echo "  Duration: ${DURATION}s per vehicle count"
echo "=========================================="
echo ""

# 获取可用 plan 和 vehicle
info "Finding available plans and vehicles..."
PLANS=$(api_get "/admin-api/transport/dispatch/plan/page?pageNo=1&pageSize=10" || echo "")
PLAN_ID=$(echo "$PLANS" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")

if [ "$PLAN_ID" = "null" ]; then
    echo "No plan found. Exiting."
    exit 1
fi

PLAN_DETAIL=$(api_get "/admin-api/transport/dispatch/plan/get?id=$PLAN_ID" || echo "")
VEHICLES=$(echo "$PLAN_DETAIL" | jq -r '[.data.items[].vehicleId] | unique | .[]' 2>/dev/null || echo "")

if [ -z "$VEHICLES" ]; then
    echo "No vehicles found. Exiting."
    exit 1
fi

VEHICLE_ARRAY=($VEHICLES)
info "Found ${#VEHICLE_ARRAY[@]} vehicles in plan $PLAN_ID"

# 性能测试函数
run_performance_test() {
    local vehicle_count=$1
    local test_vehicles=("${VEHICLE_ARRAY[@]:0:$vehicle_count}")

    echo ""
    info "Testing with $vehicle_count vehicles for ${DURATION}s..."

    # Reset all test vehicles
    for vid in "${test_vehicles[@]}"; do
        api_post "/admin-api/transport/simulation/reset?vehicleId=$vid" >/dev/null 2>&1 || true
    done
    sleep 1

    # Start simulations
    local start_time=$(date +%s)
    for vid in "${test_vehicles[@]}"; do
        api_post "/admin-api/transport/simulation/start?planId=$PLAN_ID&vehicleId=$vid&multiplier=10" >/dev/null 2>&1 &
    done
    wait
    local start_elapsed=$(( $(date +%s) - start_time ))
    result "Start $vehicle_count vehicles: ${start_elapsed}s"

    # Poll runtime for DURATION seconds
    local tick_count=0
    local total_latency=0
    local max_latency=0
    local min_latency=999999
    local test_end=$((SECONDS + DURATION))

    while [ $SECONDS -lt $test_end ]; do
        for vid in "${test_vehicles[@]}"; do
            local tick_start=$(date +%s%N)
            api_get "/admin-api/transport/simulation/runtime?vehicleId=$vid" >/dev/null 2>&1
            local tick_end=$(date +%s%N)
            local latency=$(( (tick_end - tick_start) / 1000000 )) # ms

            tick_count=$((tick_count + 1))
            total_latency=$((total_latency + latency))
            if [ $latency -gt $max_latency ]; then
                max_latency=$latency
            fi
            if [ $latency -lt $min_latency ]; then
                min_latency=$latency
            fi
        done
        sleep 1
    done

    local avg_latency=0
    if [ $tick_count -gt 0 ]; then
        avg_latency=$((total_latency / tick_count))
    fi

    result "Ticks: $tick_count"
    result "Avg latency: ${avg_latency}ms"
    result "Min latency: ${min_latency}ms"
    result "Max latency: ${max_latency}ms"

    # Count events written
    local event_count=0
    for vid in "${test_vehicles[@]}"; do
        local history=$(api_get "/admin-api/transport/simulation/history?pageNo=1&pageSize=1" || echo "")
        local run_id=$(echo "$history" | jq -r '.data.list[0].id' 2>/dev/null || echo "null")
        if [ "$run_id" != "null" ]; then
            local events=$(api_get "/admin-api/transport/simulation/events?runId=$run_id" || echo "")
            local ev_count=$(echo "$events" | jq '.data | length' 2>/dev/null || echo "0")
            event_count=$((event_count + ev_count))
        fi
    done
    result "Events written: $event_count (expected: minimal - only state changes)"

    # Reset all
    for vid in "${test_vehicles[@]}"; do
        api_post "/admin-api/transport/simulation/reset?vehicleId=$vid" >/dev/null 2>&1 || true
    done
}

# 运行测试
if [ ${#VEHICLE_ARRAY[@]} -ge 1 ]; then
    run_performance_test 1
fi

if [ ${#VEHICLE_ARRAY[@]} -ge 5 ]; then
    run_performance_test 5
fi

if [ ${#VEHICLE_ARRAY[@]} -ge 10 ]; then
    run_performance_test 10
fi

echo ""
echo "=========================================="
echo "  Performance Smoke Test Complete"
echo "=========================================="
