#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || "$2" != "--yes" ]]; then
  echo "Usage: $0 <backup.dump> --yes" >&2
  exit 2
fi

backup_file="$1"
database="${KTV_DB_NAME:-ktv}"
user="${KTV_DB_USER:-ktv}"

if [[ ! -f "${backup_file}" ]]; then
  echo "Backup not found: ${backup_file}" >&2
  exit 2
fi

docker compose stop ktv
if docker compose exec -T db pg_restore \
    -U "${user}" \
    -d "${database}" \
    --clean \
    --if-exists \
    --no-owner < "${backup_file}"; then
  :
else
  status=$?
  echo "Restore failed; ktv remains stopped for safety." >&2
  exit "${status}"
fi

if ! docker compose start ktv; then
  echo "Restore succeeded but ktv could not be started; manual recovery is required." >&2
  exit 1
fi
echo "Restore completed: ${backup_file}"
