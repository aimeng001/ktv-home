#!/usr/bin/env bash
set -euo pipefail

backup_dir="${1:-./backups}"
timestamp="$(date +%Y%m%d-%H%M%S)"
database="${KTV_DB_NAME:-ktv}"
user="${KTV_DB_USER:-ktv}"

mkdir -p "${backup_dir}"
tmp="$(mktemp "${backup_dir}/.home-ktv-${timestamp}-XXXXXX.dump")"
tmp_name="${tmp##*/}"
output="${backup_dir}/${tmp_name#.}"

cleanup() {
  rm -f -- "${tmp}"
}
trap cleanup EXIT HUP INT TERM

if docker compose exec -T db pg_dump -U "${user}" -d "${database}" --format=custom > "${tmp}"; then
  :
else
  status=$?
  echo "Backup failed; no backup was published." >&2
  exit "${status}"
fi

if ! mv -- "${tmp}" "${output}"; then
  echo "Backup failed; could not publish the dump atomically." >&2
  exit 1
fi
trap - EXIT HUP INT TERM
echo "Backup created: ${output}"
