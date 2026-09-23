#!/bin/sh
set -eu

umask 077
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/compose.prod.yml}"
DEPLOY_DIR_REAL="$(CDPATH= cd -- "$ROOT_DIR/deploy" && pwd -P)"
BACKUP_DIR="$ROOT_DIR/deploy/backups"

for required_file in "$ENV_FILE" "$COMPOSE_FILE"; do
  if [ ! -f "$required_file" ]; then
    echo "Required file is missing: $required_file" >&2
    exit 66
  fi
done
for command_name in docker gzip sha256sum date sort awk; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Required command is missing: $command_name" >&2
    exit 69
  }
done

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
BACKUP_DIR_REAL="$(CDPATH= cd -- "$BACKUP_DIR" && pwd -P)"
if [ "$BACKUP_DIR_REAL" != "$DEPLOY_DIR_REAL/backups" ]; then
  echo 'Resolved backup directory is outside the repository deployment directory.' >&2
  exit 73
fi

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

compose exec -T mysql sh -eu -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysqladmin ping --host=127.0.0.1 --user=root --silent
' >/dev/null

database_name="$(compose exec -T mysql sh -eu -c 'printf %s "$MYSQL_DATABASE"' | tr -d '\r')"
case "$database_name" in
  ''|*[!A-Za-z0-9_]*)
    echo 'MYSQL_DATABASE may contain only letters, digits, and underscores.' >&2
    exit 64
    ;;
esac

timestamp="$(date '+%Y%m%d-%H%M%S')"
backup_name="$database_name-$timestamp.sql.gz"
backup_path="$BACKUP_DIR_REAL/$backup_name"
checksum_path="$backup_path.sha256"
temporary_sql="$BACKUP_DIR_REAL/.$backup_name.$$.sql"
temporary_gzip="$BACKUP_DIR_REAL/.$backup_name.$$.gz"
temporary_checksum="$BACKUP_DIR_REAL/.$backup_name.$$.sha256"
retention_list="$BACKUP_DIR_REAL/.retention.$$.list"
published=false

cleanup() {
  rm -f "$temporary_sql" "$temporary_gzip" "$temporary_checksum" "$retention_list"
  if [ "$published" != true ]; then
    rm -f "$backup_path" "$checksum_path"
  fi
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

if [ -e "$backup_path" ] || [ -e "$checksum_path" ]; then
  echo "Backup already exists: $backup_name" >&2
  exit 73
fi

compose exec -T mysql sh -eu -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysqldump --user=root \
    --single-transaction --quick --routines --triggers --events \
    --set-gtid-purged=OFF --databases "$MYSQL_DATABASE"
' > "$temporary_sql"

gzip -c "$temporary_sql" > "$temporary_gzip"
gzip -t "$temporary_gzip"
checksum_output="$(sha256sum "$temporary_gzip")"
checksum_digest="${checksum_output%% *}"
case "$checksum_digest" in
  ''|*[!0-9a-fA-F]*)
    echo 'Backup SHA-256 generation failed.' >&2
    exit 1
    ;;
esac
if [ "${#checksum_digest}" -ne 64 ]; then
  echo 'Backup SHA-256 generation returned an invalid digest.' >&2
  exit 1
fi
printf '%s  %s\n' "$checksum_digest" "$backup_name" > "$temporary_checksum"
chmod 600 "$temporary_gzip" "$temporary_checksum"
mv "$temporary_gzip" "$backup_path"
mv "$temporary_checksum" "$checksum_path"
published=true

: > "$retention_list"
for candidate in "$BACKUP_DIR_REAL/$database_name-"*.sql.gz; do
  if [ -f "$candidate" ] && [ ! -L "$candidate" ] && [ -f "$candidate.sha256" ] && [ ! -L "$candidate.sha256" ]; then
    printf '%s\n' "$candidate" >> "$retention_list"
  fi
done
sort -r "$retention_list" | awk 'NR > 7' | while IFS= read -r old_backup; do
  case "$old_backup" in
    "$BACKUP_DIR_REAL/$database_name-"*.sql.gz) ;;
    *)
      echo 'Refusing to delete a backup outside the resolved backup directory.' >&2
      exit 73
      ;;
  esac
  rm -f "$old_backup" "$old_backup.sha256"
done

cleanup
trap - EXIT HUP INT TERM
printf 'MySQL backup created: %s\n' "$backup_name"
