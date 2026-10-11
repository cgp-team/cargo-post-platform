#!/usr/bin/env bash
# 宣传片拍摄前「刷新实况」：重跑 21-today-live.sql（自清理+按当前时刻重建当日运营态）
# 用法（本机执行，自动走 SSH 到服务器）：
#   bash sql/promo/90-refresh-live.sh
set -euo pipefail

SSH_KEY="${SSH_KEY:-$HOME/.ssh/cargo-post-actions-deploy}"
SSH_HOST="${SSH_HOST:-deploy@1.15.29.107}"
REMOTE_DIR=/tmp/cargo-post-promo

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

scp -i "${SSH_KEY}" "${SCRIPT_DIR}/21-today-live.sql" "${SCRIPT_DIR}/23-polish.sql" "${SSH_HOST}:${REMOTE_DIR}/" >/dev/null

ssh -i "${SSH_KEY}" "${SSH_HOST}" bash -s <<'EOF'
set -euo pipefail
set -a; source /opt/cargo-post-platform/.env; set +a
for f in 21-today-live 23-polish; do
  docker compose --env-file /opt/cargo-post-platform/.env \
    -f /opt/cargo-post-platform/deploy/docker-compose.yml \
    exec -T mysql mysql --default-character-set=utf8mb4 -u root -p"${DB_PASSWORD}" "${DB_NAME}" \
    < "/tmp/cargo-post-promo/${f}.sql" > /dev/null
done
EOF

echo "OK: 当日实况已刷新到当前时刻（21 实况层 + 23 细节层）"
