#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
COMPOSE_FILE="${ROOT_DIR}/deploy/docker-compose.yml"

command -v docker >/dev/null || { echo "docker is required" >&2; exit 2; }
docker compose version >/dev/null || { echo "Docker Compose v2 is required" >&2; exit 2; }
[[ -f "${ENV_FILE}" ]] || { echo "Copy .env.example to ${ENV_FILE} and configure it" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

required=(DB_NAME DB_USER DB_PASSWORD REDIS_PASSWORD MINIO_ROOT_USER MINIO_ROOT_PASSWORD ALGORITHM_BASE_URL)
for name in "${required[@]}"; do
  [[ -n "${!name:-}" ]] || { echo "Required environment variable is empty: ${name}" >&2; exit 2; }
done

compose=(docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}")
"${compose[@]}" config --quiet
"${compose[@]}" pull --ignore-buildable
"${compose[@]}" build mock-algorithm
"${compose[@]}" build algorithm
"${compose[@]}" up -d --remove-orphans

CHECK_BACKEND="${CHECK_BACKEND:-false}" ENV_FILE="${ENV_FILE}" "${ROOT_DIR}/deploy/scripts/health-check.sh"
