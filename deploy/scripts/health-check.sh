#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
COMPOSE_FILE="${ROOT_DIR}/deploy/docker-compose.yml"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Environment file not found: ${ENV_FILE}" >&2
  echo "Copy .env.example to .env and set development credentials." >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")

echo "Checking MySQL..."
"${compose[@]}" exec -T mysql mysqladmin ping -h 127.0.0.1 -u"${DB_USER}" -p"${DB_PASSWORD}" --silent

echo "Checking Redis..."
"${compose[@]}" exec -T redis redis-cli -a "${REDIS_PASSWORD}" --no-auth-warning ping | grep -q PONG

echo "Checking Mock algorithm..."
curl --fail --silent --show-error "http://127.0.0.1:${MOCK_ALGORITHM_PORT:-18080}/health" >/dev/null

if [[ "${CHECK_BACKEND:-true}" == "true" ]]; then
  echo "Checking business backend..."
  curl --fail --silent --show-error "${BACKEND_HEALTH_URL:-http://127.0.0.1:${BACKEND_PORT:-48080}/actuator/health}" >/dev/null
fi

echo "All requested health checks passed."
