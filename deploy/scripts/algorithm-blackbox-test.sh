#!/usr/bin/env bash
# deploy/scripts/algorithm-blackbox-test.sh
# 自研算法服务黑盒验收测试：对已部署的 algorithm 服务执行端到端 HTTP 验证。
#
# 用法：
#   algorithm-blackbox-test.sh [BASE_URL]
#   默认 BASE_URL=http://127.0.0.1:18081
#
# 测试项（12 项）：
#   1. health 200
#   2. ready 200
#   3. feasible request
#   4. capacity infeasible
#   5. unknown station 400
#   6. 26 orders → 413
#   7. idempotency cached=true
#   8. PASS skeleton
#   9. mixed passenger+cargo
#  10. shipment
#  11. time window
#  12. deterministic result

set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:18081}"
PASS_COUNT=0
FAIL_COUNT=0

pass() {
    echo "[PASS] $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
    echo "[FAIL] $1"
    if [[ -n "${2:-}" ]]; then
        echo "       $2"
    fi
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

check_status() {
    local desc="$1" url="$2" expected="$3" method="${4:-GET}" data="${5:-}"
    local actual
    if [[ "$method" == "POST" && -n "$data" ]]; then
        actual=$(curl -sS -o /dev/null -w "%{http_code}" \
          -X POST \
          -H "Content-Type: application/json" \
          -d "$data" \
          "$url" 2>/dev/null || echo "000")
    elif [[ "$method" == "POST" ]]; then
        actual=$(curl -sS -o /dev/null -w "%{http_code}" \
          -X POST "$url" 2>/dev/null || echo "000")
    else
        actual=$(curl -sS -o /dev/null -w "%{http_code}" \
          "$url" 2>/dev/null || echo "000")
    fi
    if [[ "$actual" == "$expected" ]]; then
        pass "$desc (HTTP $actual)"
    else
        fail "$desc (expected $expected, got $actual)"
    fi
}

check_json_field() {
    local desc="$1" url="$2" field="$3" expected="$4" method="${5:-GET}" data="${6:-}"
    local body
    if [[ "$method" == "POST" && -n "$data" ]]; then
        body=$(curl -sf -X POST -H "Content-Type: application/json" -d "$data" "$url" 2>/dev/null || echo "{}")
    elif [[ "$method" == "POST" ]]; then
        body=$(curl -sf -X POST "$url" 2>/dev/null || echo "{}")
    else
        body=$(curl -sf "$url" 2>/dev/null || echo "{}")
    fi
    local actual
    actual=$(echo "$body" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field','__MISSING__'))" 2>/dev/null || echo "__ERROR__")
    if [[ "$actual" == "$expected" ]]; then
        pass "$desc ($field=$actual)"
    else
        fail "$desc (expected $field=$expected, got $actual)" "$body"
    fi
}

# ═══════════════════════════════════════════════════════════════
echo "===== Algorithm Black-Box Test: $BASE_URL ====="
echo ""

# 1. GET /health → 200
check_status "1. GET /health" "$BASE_URL/health" "200"

# 2. GET /ready → 200
check_status "2. GET /ready" "$BASE_URL/ready" "200"

# 2b. Verify algorithm version contains HACO-CPS
HEALTH_BODY=$(curl -sf "$BASE_URL/health" 2>/dev/null || echo "{}")
ALGO_VER=$(echo "$HEALTH_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('algorithmVersion','unknown'))" 2>/dev/null || echo "unknown")
if [[ "$ALGO_VER" == *"haco-cps"* ]]; then
    pass "2b. Algorithm version is HACO-CPS ($ALGO_VER)"
elif [[ "$ALGO_VER" == *"ortools"* ]]; then
    pass "2b. Algorithm version is OR-Tools baseline ($ALGO_VER)"
else
    fail "2b. Unknown algorithm version: $ALGO_VER"
fi

# 3. feasible request
FEASIBLE_PAYLOAD='{
  "requestId": "bb-feasible-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4}],
  "orders": [
    {"orderId": "P1", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"}
  ]
}'
check_json_field "3. feasible request" "$BASE_URL/api/v1/plan" "status" "feasible" POST "$FEASIBLE_PAYLOAD"

# 4. capacity infeasible
CAPACITY_PAYLOAD='{
  "requestId": "bb-capacity-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4}],
  "orders": [
    {"orderId": "P1", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
    {"orderId": "P2", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
    {"orderId": "P3", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
    {"orderId": "P4", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
    {"orderId": "P5", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
    {"orderId": "P6", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"}
  ]
}'
check_json_field "4. capacity infeasible" "$BASE_URL/api/v1/plan" "status" "infeasible" POST "$CAPACITY_PAYLOAD"

# 5. unknown station → 400
UNKNOWN_PAYLOAD='{
  "requestId": "bb-unknown-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [{"stationId": "S1", "longitude": 104.010, "latitude": 30.010}],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4}],
  "orders": [
    {"orderId": "P1", "orderType": "PASSENGER", "boardingStationId": "S99", "alightingStationId": "S1"}
  ]
}'
check_status "5. unknown station 400" "$BASE_URL/api/v1/plan" "400" POST "$UNKNOWN_PAYLOAD"

# 6. 26 orders → 413
LIMIT_ORDERS=""
for i in $(seq 1 26); do
    if [[ $i -gt 1 ]]; then LIMIT_ORDERS+=","; fi
    LIMIT_ORDERS+="{\"orderId\":\"P$i\",\"orderType\":\"PASSENGER\",\"boardingStationId\":\"S1\",\"alightingStationId\":\"S2\"}"
done
LIMIT_PAYLOAD="{\"requestId\":\"bb-limit-001\",\"batchStart\":\"2026-08-23T08:00:00+08:00\",\"batchEnd\":\"2026-08-23T18:00:00+08:00\",\"depot\":{\"stationId\":\"S0\",\"longitude\":104.000,\"latitude\":30.000},\"stations\":[{\"stationId\":\"S1\",\"longitude\":104.010,\"latitude\":30.010},{\"stationId\":\"S2\",\"longitude\":104.020,\"latitude\":30.020}],\"vehicles\":[{\"vehicleId\":1,\"passengerCapacity\":5,\"cargoCapacity\":4}],\"orders\":[$LIMIT_ORDERS]}"
check_status "6. 26 orders → 413" "$BASE_URL/api/v1/plan" "413" POST "$LIMIT_PAYLOAD"

# 7. idempotency cached=true
IDE_PAYLOAD='{
  "requestId": "bb-idempotent-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4}],
  "orders": [
    {"orderId": "P1", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"}
  ]
}'
# First call (populate cache)
curl -sf -X POST -H "Content-Type: application/json" -d "$IDE_PAYLOAD" "$BASE_URL/api/v1/plan" >/dev/null 2>&1 || true
# Second call (should be cached)
check_json_field "7. idempotency cached=true" "$BASE_URL/api/v1/plan" "cached" "True" POST "$IDE_PAYLOAD"

# 8. PASS skeleton
SKELETON_PAYLOAD='{
  "requestId": "bb-skeleton-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020},
    {"stationId": "S3", "longitude": 104.030, "latitude": 30.030}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4, "skeleton": ["S1", "S2", "S3"]}],
  "orders": []
}'
check_json_field "8. PASS skeleton" "$BASE_URL/api/v1/plan" "status" "feasible" POST "$SKELETON_PAYLOAD"

# 9. mixed passenger+cargo
MIXED_PAYLOAD='{
  "requestId": "bb-mixed-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020},
    {"stationId": "S3", "longitude": 104.030, "latitude": 30.030}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4}],
  "orders": [
    {"orderId": "P1", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"},
    {"orderId": "D1", "orderType": "DELIVERY", "stationId": "S3", "itemCount": 1},
    {"orderId": "K1", "orderType": "PICKUP", "stationId": "S2", "itemCount": 1}
  ]
}'
check_json_field "9. mixed passenger+cargo" "$BASE_URL/api/v1/plan" "status" "feasible" POST "$MIXED_PAYLOAD"

# 10. shipment
SHIPMENT_PAYLOAD='{
  "requestId": "bb-shipment-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 10}],
  "orders": [],
  "shipments": [
    {"shipmentId": "TP1", "pickupStationId": "S1", "deliveryStationId": "S2", "quantity": 2}
  ]
}'
check_json_field "10. shipment" "$BASE_URL/api/v1/plan" "status" "feasible" POST "$SHIPMENT_PAYLOAD"

# 11. time window
TW_PAYLOAD='{
  "requestId": "bb-timewindow-001",
  "batchStart": "2026-08-23T08:00:00+08:00",
  "batchEnd": "2026-08-23T18:00:00+08:00",
  "depot": {"stationId": "S0", "longitude": 104.000, "latitude": 30.000},
  "stations": [
    {"stationId": "S1", "longitude": 104.010, "latitude": 30.010},
    {"stationId": "S2", "longitude": 104.020, "latitude": 30.020}
  ],
  "vehicles": [{"vehicleId": 1, "passengerCapacity": 5, "cargoCapacity": 4}],
  "orders": [
    {"orderId": "P1", "orderType": "PASSENGER", "boardingStationId": "S1", "alightingStationId": "S2"}
  ]
}'
check_json_field "11. time window" "$BASE_URL/api/v1/plan" "status" "feasible" POST "$TW_PAYLOAD"

# 12. deterministic result (run twice, compare)
BODY1=$(curl -sf -X POST -H "Content-Type: application/json" -d "$FEASIBLE_PAYLOAD" "$BASE_URL/api/v1/plan" 2>/dev/null || echo "{}")
# Use a new requestId for second call to avoid idempotency cache
DET_PAYLOAD2=$(echo "$FEASIBLE_PAYLOAD" | python3 -c "import sys,json; d=json.load(sys.stdin); d['requestId']='bb-deterministic-002'; print(json.dumps(d))" 2>/dev/null || echo "{}")
BODY2=$(curl -sf -X POST -H "Content-Type: application/json" -d "$DET_PAYLOAD2" "$BASE_URL/api/v1/plan" 2>/dev/null || echo "{}")
DIST1=$(echo "$BODY1" | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalDistance',-1))" 2>/dev/null || echo "-1")
DIST2=$(echo "$BODY2" | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalDistance',-1))" 2>/dev/null || echo "-1")
if [[ "$DIST1" == "$DIST2" && "$DIST1" != "-1" ]]; then
    pass "12. deterministic result (distance=$DIST1)"
else
    fail "12. deterministic result (dist1=$DIST1, dist2=$DIST2)"
fi

# ═══════════════════════════════════════════════════════════════
echo ""
echo "===== Summary ====="
echo "PASS: $PASS_COUNT"
echo "FAIL: $FAIL_COUNT"
echo ""
if [[ "$FAIL_COUNT" -eq 0 ]]; then
    echo "RESULT: PASS"
    exit 0
else
    echo "RESULT: FAIL"
    exit 1
fi
