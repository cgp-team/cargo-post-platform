#!/usr/bin/env bash
# 后端写链路体检（默认只读探测 + 一次可回滚的最小写自检）
#
# 用途：当小程序出现「登录收不到验证码 / 商城下单失败 / 寄货提交失败」且接口统一返回
#       {"code":500,"msg":"系统异常"} 时，用本脚本一次性定位是后端基础设施（Redis/MySQL/磁盘）
#       还是业务数据问题。读接口正常但写接口全 500，最常见原因是后端连的 Redis 写命令被拒
#       （jar 内 application-dev.yaml 默认指向 yudao 公共演示 Redis）。
#
# 用法（在服务器仓库根目录执行）：
#   bash deploy/scripts/diagnose-backend.sh
# 可选环境变量：API（默认 http://127.0.0.1:48080）、ENV_FILE（默认 /opt/cargo-post-platform/.env）
set -uo pipefail

API="${API:-http://127.0.0.1:48080}"
ENV_FILE="${ENV_FILE:-/opt/cargo-post-platform/.env}"
COMPOSE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

section() { printf '\n===== %s =====\n' "$1"; }

section "1. 后端健康"
curl -s --max-time 5 "${API}/actuator/health" || echo "(健康检查不可达)"

section "2. 读接口（对照基线，正常应 code=0）"
curl -s --max-time 10 "${API}/app-api/transport/product/list" | head -c 200; echo

section "3. 写链路探测：发送短信验证码（走 Redis 写）"
echo -n "send-sms-code: "
curl -s --max-time 10 -X POST -H 'Content-Type: application/json' \
  -d '{"mobile":"13800000000","scene":1}' "${API}/app-api/member/auth/send-sms-code"; echo
echo "说明：code=500 系统异常 → 后端写 Redis 失败（登录/下单/寄货提交会一并失败）；"
echo "      返回业务错误码（如验证码相关）→ 写链路正常，问题在别处。"

section "4. 写链路探测：手机号+密码登录（走 Redis 写）"
curl -s --max-time 10 -X POST -H 'Content-Type: application/json' \
  -d '{"mobile":"13800000000","password":"123456"}' "${API}/app-api/member/auth/login"; echo

section "5. 磁盘空间（写失败常见根因）"
df -h | head -8

section "6. 本机 Redis 容器读写自检"
if [ -f "${ENV_FILE}" ]; then
  set -a; source "${ENV_FILE}"; set +a
  COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_ROOT}/deploy/docker-compose.yml")
  if "${COMPOSE[@]}" ps redis 2>/dev/null | grep -q redis; then
    echo -n "SET/GET: "
    "${COMPOSE[@]}" exec -T redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning set cargo-post:diag ok >/dev/null 2>&1 \
      && "${COMPOSE[@]}" exec -T redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning get cargo-post:diag \
      || echo "写入或读回失败"
    echo
    echo -n "当前 Redis 键数量: "
    "${COMPOSE[@]}" exec -T redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning dbsize 2>/dev/null || echo "(不可用)"
  else
    echo "本机 redis 容器未运行"
  fi
else
  echo "${ENV_FILE} 不存在，跳过（无法获取 Redis 口令）"
fi

section "7. MySQL 写入自检（建/删探针表，可重复执行）"
if [ -f "${ENV_FILE}" ]; then
  set -a; source "${ENV_FILE}"; set +a
  COMPOSE=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_ROOT}/deploy/docker-compose.yml")
  MYSQL=("${COMPOSE[@]}" exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}")
  "${MYSQL[@]}" "${DB_NAME}" -e "DROP TABLE IF EXISTS _diag_write_probe; CREATE TABLE _diag_write_probe (id int primary key); DROP TABLE _diag_write_probe;" \
    && echo "MySQL 写入正常" || echo "MySQL 写入失败（检查磁盘/只读挂载）"
  echo -n "read_only 状态: "
  "${MYSQL[@]}" -N -e "SELECT @@global.read_only, @@global.super_read_only;" 2>/dev/null || echo "(不可用)"

  echo "--- 关键表列数（缺表/缺列会让登录、下单类接口 500）---"
  for t in member_user member_address transport_product transport_product_order transport_cargo_order; do
    cnt=$("${MYSQL[@]}" "${DB_NAME}" -N -e \
      "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='${DB_NAME}' AND TABLE_NAME='${t}';" 2>/dev/null)
    if [ -z "${cnt}" ]; then
      echo "  ${t}: 表不存在或不可查询"
    elif [ "${cnt}" = "0" ]; then
      echo "  ${t}: 表不存在"
    else
      echo "  ${t}: ${cnt} 列"
    fi
  done
fi

section "8. 服务状态与最近错误日志"
systemctl status cargo-post -n 10 --no-pager 2>/dev/null || echo "(无 systemctl 权限，可用 sudo systemctl status cargo-post)"
echo "--- 最近含 redis/exception/error 的日志 ---"
journalctl -u cargo-post -n 300 --no-pager 2>/dev/null | grep -Ei 'redis|exception|系统异常|Connection refused|NOAUTH|OOM' | tail -20 \
  || echo "(无 journalctl 权限，可执行： sudo journalctl -u cargo-post -n 300 --no-pager | grep -Ei 'redis|exception')"

printf '\n结论建议：第 3/4 步返回 500 且第 6 步本机 Redis 读写正常 → 让后端改连本机 Redis：\n'
printf '  1) 在 /opt/cargo-post/app.env 追加：\n'
printf '     SPRING_DATA_REDIS_HOST=127.0.0.1\n     SPRING_DATA_REDIS_PORT=6379\n     SPRING_DATA_REDIS_DATABASE=1\n     SPRING_DATA_REDIS_PASSWORD=<.env 中的 REDIS_PASSWORD>\n'
printf '  2) sudo systemctl restart cargo-post\n'
printf '  3) 重新执行第 3/4 步确认不再 500\n'
printf '（部署流水线已内置同样的自检与切换逻辑，见 .github/workflows/deploy-dev.yml）\n'
