#!/usr/bin/env bash
# app-api-smoke.sh — 小程序端 /app-api 冒烟测试
#
# 以小程序（cargo-post-miniprogram）的接口契约为准，验证后端关键端点可达、
# 返回 yudao 标准格式（code==0 或预期业务错误码，绝不接受 404/502）。
#
# 用法：
#   bash deploy/scripts/app-api-smoke.sh                      # 默认打 https 域名或 http://1.15.29.107
#   BASE_URL=http://127.0.0.1:48080 bash deploy/scripts/app-api-smoke.sh   # 服务器本机直连后端
set -uo pipefail

BASE_URL="${BASE_URL:-http://1.15.29.107/api}"
PASS=0; FAIL=0

# check <名称> <期望> <实际> —— 期望为 "ok"（code==0）或 "biz"（业务错误码，非 0/404/500 级）
check() {
  local name="$1" expect="$2" body="$3" http="$4"
  local code
  code="$(printf '%s' "$body" | sed -n 's/.*"code":\([0-9]*\).*/\1/p' | head -1)"
  if [[ "$http" == "404" || "$http" == "502" || "$http" == "000" ]]; then
    echo "FAIL  $name  (http=$http，端点不可达)"; FAIL=$((FAIL+1)); return
  fi
  case "$expect" in
    ok)  [[ "$code" == "0" ]]            && { echo "PASS  $name"; PASS=$((PASS+1)); } || { echo "FAIL  $name  (code=$code http=$http)"; FAIL=$((FAIL+1)); } ;;
    biz) [[ -n "$code" && "$code" != "0" ]] && { echo "PASS  $name  (业务可达, code=$code)"; PASS=$((PASS+1)); } || { echo "FAIL  $name  (code=$code http=$http)"; FAIL=$((FAIL+1)); } ;;
  esac
}

req() { # req <METHOD> <PATH> [DATA] -> 输出 body 与 http code
  local method="$1" path="$2" data="${3:-}"
  if [[ -n "$data" ]]; then
    curl -s -m 10 -X "$method" "$BASE_URL$path" -H 'Content-Type: application/json' -d "$data" -w $'\n%{http_code}'
  else
    curl -s -m 10 -X "$method" "$BASE_URL$path" -w $'\n%{http_code}'
  fi
}

run() { # run <名称> <期望> <METHOD> <PATH> [DATA]
  local out; out="$(req "$3" "$4" "${5:-}")"
  check "$1" "$2" "$(printf '%s' "$out" | head -1)" "$(printf '%s' "$out" | tail -1 | tr -d '[:space:]')"
}

echo "== 小程序 /app-api 冒烟 @ $BASE_URL =="

# —— 免登录端点（小程序首页/公交/商城/地址依赖） ——
run "notice/list 平台公告"      ok  GET  "/app-api/transport/notice/list"
run "product/list 推荐商品"     ok  GET  "/app-api/transport/product/list"
run "product/page 商品分页"     ok  GET  "/app-api/transport/product/page?pageNo=1&pageSize=1"
run "area/tree 省市区"          ok  GET  "/app-api/system/area/tree"
run "bus/nearby 附近公交"       ok  GET  "/app-api/transport/bus/nearby?longitude=106.5765&latitude=29.5325"
run "bus/lines 线路几何"        ok  GET  "/app-api/transport/bus/lines"

# —— 鉴权链路（用错误凭据打，预期返回业务错误码而非异常） ——
run "auth/sms-login 可达"       biz POST "/app-api/member/auth/sms-login" '{"mobile":"13800000000","code":"000000"}'
run "auth/login 可达"           biz POST "/app-api/member/auth/login" '{"username":"__smoke__","password":"__smoke__"}'
run "auth/weixin-login 可达"    biz POST "/app-api/member/auth/weixin-mini-app-login" '{"loginCode":"__smoke__","phoneCode":"__smoke__","state":"smoke"}'

# —— 登录态端点（不带 token 应 401，证明鉴权链路与路由存在） ——
run "member/user/get 鉴权"      biz GET  "/app-api/member/user/get"
run "send/page 鉴权"            biz GET  "/app-api/transport/send/page?pageNo=1&pageSize=1"
run "driver/profile 鉴权"       biz GET  "/app-api/transport/driver/profile"
run "notification 鉴权"         biz GET  "/app-api/transport/notification/page?pageNo=1&pageSize=1"

# —— 管理端代理（401 而非 404，证明 /admin-api 路由在） ——
run "admin-api 路由"            biz GET  "/admin-api/system/auth/get-permission-info"

echo
echo "== 结果: $PASS 通过 / $FAIL 失败 =="
[[ "$FAIL" == "0" ]]
