#!/bin/sh
set -eu

umask 077
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd -P)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env.prod}"
COMPOSE_FILE="${COMPOSE_FILE:-$ROOT_DIR/compose.prod.yml}"
DEPLOY_DIR_REAL="$(CDPATH= cd -- "$ROOT_DIR/deploy" && pwd -P)"
BACKUP_DIR="$ROOT_DIR/deploy/backups"

if [ "$#" -ne 2 ] || [ "$2" != '--confirm-restore' ]; then
  echo 'Usage: deploy/scripts/restore-mysql.sh BACKUP.sql.gz --confirm-restore' >&2
  exit 64
fi
for required_file in "$ENV_FILE" "$COMPOSE_FILE"; do
  if [ ! -f "$required_file" ]; then
    echo "Required file is missing: $required_file" >&2
    exit 66
  fi
done
for command_name in docker gzip sha256sum mkfifo mktemp; do
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

backup_input=$1
backup_parent="$(CDPATH= cd -- "$(dirname -- "$backup_input")" 2>/dev/null && pwd -P)" || {
  echo 'Backup parent directory does not exist.' >&2
  exit 66
}
backup_name="$(basename -- "$backup_input")"
backup_path="$backup_parent/$backup_name"
checksum_path="$backup_path.sha256"
if [ "$backup_parent" != "$BACKUP_DIR_REAL" ]; then
  echo 'Backup must be located directly inside deploy/backups.' >&2
  exit 73
fi
if [ ! -f "$backup_path" ] || [ -L "$backup_path" ] || [ ! -f "$checksum_path" ] || [ -L "$checksum_path" ]; then
  echo 'Backup or SHA-256 companion file is missing or unsafe.' >&2
  exit 66
fi

gzip -t "$backup_path"
checksum_line="$(cat "$checksum_path")"
set -- $checksum_line
if [ "$#" -ne 2 ] || [ "$2" != "$backup_name" ]; then
  echo 'Backup SHA-256 companion file has an invalid format.' >&2
  exit 65
fi
expected_checksum=$1
actual_output="$(sha256sum "$backup_path")"
actual_checksum="${actual_output%% *}"
if [ "$expected_checksum" != "$actual_checksum" ]; then
  echo 'Backup SHA-256 verification failed.' >&2
  exit 65
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
case "$backup_name" in
  "$database_name-"*.sql.gz) ;;
  *)
    echo "Backup filename does not match target database: $database_name" >&2
    exit 65
    ;;
esac

restore_dir="$(mktemp -d "${TMPDIR:-/tmp}/novel-restore.XXXXXX")"
restore_fifo="$restore_dir/restore.sql"
gzip_pid=''
cleanup() {
  if [ -n "$gzip_pid" ]; then kill "$gzip_pid" 2>/dev/null || true; fi
  rm -f "$restore_fifo"
  rmdir "$restore_dir" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
mkfifo "$restore_fifo"

printf 'Restoring backup into database: %s\n' "$database_name"
gzip -dc "$backup_path" > "$restore_fifo" &
gzip_pid=$!
mysql_status=0
compose exec -T mysql sh -eu -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysql --default-character-set=utf8mb4 --batch --user=root
' < "$restore_fifo" || mysql_status=$?
gzip_status=0
wait "$gzip_pid" || gzip_status=$?
gzip_pid=''
if [ "$gzip_status" -ne 0 ] || [ "$mysql_status" -ne 0 ]; then
  echo "Restore failed: gzip=$gzip_status mysql=$mysql_status" >&2
  exit 1
fi

cleanup
trap - EXIT HUP INT TERM
printf 'MySQL restore completed from: %s\n' "$backup_name"
