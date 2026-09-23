#!/bin/sh
set -eu

SOURCE_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/novel-backup-test.XXXXXX")"
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT HUP INT TERM

REPO="$TEST_ROOT/repo"
FAKE_BIN="$TEST_ROOT/fake-bin"
mkdir -p "$REPO/deploy/scripts" "$REPO/deploy/backups" "$FAKE_BIN"
cp "$SOURCE_ROOT/deploy/scripts/backup-mysql.sh" "$REPO/deploy/scripts/"
cp "$SOURCE_ROOT/deploy/scripts/restore-mysql.sh" "$REPO/deploy/scripts/"
: > "$REPO/.env.prod"
: > "$REPO/compose.prod.yml"

cat > "$FAKE_BIN/docker" <<'EOF'
#!/bin/sh
case "$*" in
  *'mysqladmin ping'*) exit 0 ;;
  *'printf %s "$MYSQL_DATABASE"'*) printf '%s' novel_plus ;;
  *mysqldump*) exec mysqldump ;;
  *'exec mysql --default-character-set'*) cat > "$FAKE_MYSQL_INPUT"; exit 0 ;;
  *) echo "Unexpected fake docker call: $*" >&2; exit 70 ;;
esac
EOF
cat > "$FAKE_BIN/mysqldump" <<'EOF'
#!/bin/sh
printf '%s\n' 'CREATE TABLE backup_probe (id bigint);' 'INSERT INTO backup_probe VALUES (1);'
EOF
cat > "$FAKE_BIN/gzip" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_GZIP_LOG"
exec /bin/gzip "$@"
EOF
cat > "$FAKE_BIN/date" <<'EOF'
#!/bin/sh
printf '%s\n' '20990101-010101'
EOF
chmod 700 "$FAKE_BIN/docker" "$FAKE_BIN/mysqldump" "$FAKE_BIN/gzip" "$FAKE_BIN/date"

export PATH="$FAKE_BIN:$PATH"
export FAKE_GZIP_LOG="$TEST_ROOT/gzip.calls"
export FAKE_MYSQL_INPUT="$TEST_ROOT/mysql.input"
outside_sentinel="$TEST_ROOT/must-survive"
printf '%s\n' safe > "$outside_sentinel"

index=1
while [ "$index" -le 9 ]; do
  suffix="$(printf '%06d' "$index")"
  filename="novel_plus-20000101-$suffix.sql.gz"
  printf '%s\n' "old-$index" | /bin/gzip -c > "$REPO/deploy/backups/$filename"
  digest="$(/bin/busybox sha256sum "$REPO/deploy/backups/$filename" | awk '{print $1}')"
  printf '%s  %s\n' "$digest" "$filename" > "$REPO/deploy/backups/$filename.sha256"
  index=$((index + 1))
done

"$REPO/deploy/scripts/backup-mysql.sh" >/dev/null
gzip_count="$(find "$REPO/deploy/backups" -maxdepth 1 -type f -name '*.sql.gz' | wc -l | tr -d ' ')"
checksum_count="$(find "$REPO/deploy/backups" -maxdepth 1 -type f -name '*.sql.gz.sha256' | wc -l | tr -d ' ')"
[ "$gzip_count" = 7 ] && [ "$checksum_count" = 7 ] || {
  echo "Retention failed: gzip=$gzip_count checksum=$checksum_count" >&2
  exit 1
}
[ -f "$outside_sentinel" ] || { echo 'Retention escaped the isolated backup directory.' >&2; exit 1; }
[ ! -e "$REPO/deploy/backups/novel_plus-20000101-000001.sql.gz" ] || {
  echo 'Retention did not remove the oldest successful backup pair.' >&2
  exit 1
}

latest="$REPO/deploy/backups/novel_plus-20990101-010101.sql.gz"
[ -f "$latest" ] && [ -f "$latest.sha256" ] || { echo 'New backup pair is missing.' >&2; exit 1; }
[ "$(stat -c '%a' "$REPO/deploy/backups")" = 700 ] || { echo 'Backup directory permissions are not restrictive.' >&2; exit 1; }
[ "$(stat -c '%a' "$latest")" = 600 ] || { echo 'Backup file permissions are not restrictive.' >&2; exit 1; }
(cd "$REPO/deploy/backups" && /bin/gzip -t "$(basename "$latest")" && /bin/busybox sha256sum -c "$(basename "$latest").sha256" >/dev/null)

if "$REPO/deploy/scripts/restore-mysql.sh" "$latest" >/dev/null 2>&1; then
  echo 'Restore succeeded without the literal confirmation flag.' >&2
  exit 1
fi
"$REPO/deploy/scripts/restore-mysql.sh" "$latest" --confirm-restore >/dev/null
grep -q 'CREATE TABLE backup_probe' "$FAKE_MYSQL_INPUT" || { echo 'Restore did not stream SQL to MySQL.' >&2; exit 1; }

outside_backup="$TEST_ROOT/novel_plus-20990101-020202.sql.gz"
cp "$latest" "$outside_backup"
outside_digest="$(/bin/busybox sha256sum "$outside_backup" | awk '{print $1}')"
printf '%s  %s\n' "$outside_digest" "$(basename "$outside_backup")" > "$outside_backup.sha256"
if "$REPO/deploy/scripts/restore-mysql.sh" "$outside_backup" --confirm-restore >/dev/null 2>&1; then
  echo 'Restore accepted a backup outside deploy/backups.' >&2
  exit 1
fi

grep -q -- '-t' "$FAKE_GZIP_LOG" || { echo 'Backup/restore did not run gzip integrity checks.' >&2; exit 1; }
printf '%s\n' 'Production backup operation behavior passed.'
