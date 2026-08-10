#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: backup.sh [--env FILE] [--output DIR] [--retention DAYS]

Creates a gzip-compressed MySQL logical backup through Docker Compose.
This is a development baseline, not a complete disaster-recovery solution.
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
OUTPUT_DIR=""
RETENTION_DAYS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENV_FILE="$2"; shift 2 ;;
    --output) OUTPUT_DIR="$2"; shift 2 ;;
    --retention) RETENTION_DAYS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Environment file not found: ${ENV_FILE}" >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

OUTPUT_DIR="${OUTPUT_DIR:-${BACKUP_DIR:-${ROOT_DIR}/backups}}"
RETENTION_DAYS="${RETENTION_DAYS:-${BACKUP_RETENTION_DAYS:-14}}"
mkdir -p "${OUTPUT_DIR}"

timestamp="$(date '+%Y%m%d-%H%M%S')"
backup_file="${OUTPUT_DIR}/${DB_NAME}-${timestamp}.sql.gz"
compose=(docker compose --env-file "${ENV_FILE}" -f "${ROOT_DIR}/deploy/docker-compose.yml")

echo "Creating ${backup_file}..."
"${compose[@]}" exec -T -e MYSQL_PWD="${DB_PASSWORD}" mysql \
  mysqldump --default-character-set=utf8mb4 --single-transaction --routines --triggers -u"${DB_USER}" "${DB_NAME}" | gzip -9 >"${backup_file}"

find "${OUTPUT_DIR}" -type f -name "${DB_NAME}-*.sql.gz" -mtime "+${RETENTION_DAYS}" -delete
echo "Backup complete. Restore must be tested before production use."
