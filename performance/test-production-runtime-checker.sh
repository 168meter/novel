#!/bin/sh
set -eu

SOURCE_ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/novel-runtime-test.XXXXXX")"
cleanup() { rm -rf "$TEST_ROOT"; }
trap cleanup EXIT HUP INT TERM

REPO="$TEST_ROOT/repo"
FAKE_BIN="$TEST_ROOT/fake-bin"
mkdir -p "$REPO/performance" "$REPO/deploy/scripts" "$REPO/deploy/backups" "$FAKE_BIN"
cp "$SOURCE_ROOT/performance/check-production-deployment.sh" "$REPO/performance/"
: > "$REPO/.env.prod"
: > "$REPO/compose.prod.yml"

cat > "$REPO/deploy/scripts/backup-mysql.sh" <<'EOF'
#!/bin/sh
set -eu
: > "$FAKE_BACKUP_CALLED"
EOF
chmod 700 "$REPO/deploy/scripts/backup-mysql.sh"

cat > "$FAKE_BIN/docker" <<'EOF'
#!/bin/sh
case "${1:-}" in
  compose)
    case "$*" in
      *' ps --services --status running')
        printf '%s\n' nginx novel-front mysql redis kafka prometheus grafana
        ;;
      *' ps -q '*)
        for last_arg do :; done
        printf 'id-%s\n' "$last_arg"
        ;;
      *' exec -T kafka '*)
        lag="${FAKE_LAG:-0}"
        printf '%s\n' \
          'GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID' \
          "fixture-group fixture-topic 0 10 $((10 + lag)) $lag - - -"
        ;;
      *) echo "Unexpected fake compose call: $*" >&2; exit 70 ;;
    esac
    ;;
  inspect)
    for last_arg do :; done
    case "$*" in
      *'.State.Status'*)
        if [ "${FAKE_UNHEALTHY:-}" = "$last_arg" ]; then printf '%s\n' 'running unhealthy'; else printf '%s\n' 'running healthy'; fi
        ;;
      *'.HostConfig.Memory'*)
        case "$last_arg" in
          id-nginx) value=67108864 ;;
          id-novel-front) value=939524096 ;;
          id-mysql) value=805306368 ;;
          id-redis) value=268435456 ;;
          id-kafka) value=671088640 ;;
          id-prometheus) value=402653184 ;;
          id-grafana) value=268435456 ;;
          *) exit 70 ;;
        esac
        printf '%s\n' "$value"
        ;;
      *) exit 70 ;;
    esac
    ;;
  stats) printf '%s\n' '10.00%' ;;
  *) echo "Unexpected fake docker call: $*" >&2; exit 70 ;;
esac
EOF

cat > "$FAKE_BIN/ss" <<'EOF'
#!/bin/sh
printf '%s\n' \
  'LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*' \
  'LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*' \
  'LISTEN 0 4096 127.0.0.1:9090 0.0.0.0:*' \
  'LISTEN 0 4096 127.0.0.1:3000 0.0.0.0:*'
if [ "${FAKE_BAD_SOCKET:-false}" = true ]; then
  printf '%s\n' 'LISTEN 0 4096 0.0.0.0:3306 0.0.0.0:*'
fi
EOF

cat > "$FAKE_BIN/curl" <<'EOF'
#!/bin/sh
case "$*" in
  *'127.0.0.1:9090/api/v1/query'*)
    printf '%s\n' '{"status":"success","data":{"resultType":"vector","result":[{"metric":{"job":"novel-front"},"value":[1,"1"]}]}}'
    ;;
  *'/actuator/health'*) printf '%s' 404 ;;
  *) printf '%s' 200 ;;
esac
EOF

cat > "$FAKE_BIN/free" <<'EOF'
#!/bin/sh
printf '%s\n' \
  '               total        used        free      shared  buff/cache   available' \
  'Mem:      4294967296  2147483648   536870912           0  1610612736  1073741824' \
  'Swap:     4294967296           0  4294967296'
EOF

cat > "$FAKE_BIN/df" <<'EOF'
#!/bin/sh
printf '%s\n' \
  'Filesystem 1024-blocks Used Available Capacity Mounted on' \
  '/dev/fake 52428800 10485760 41943040 20% /fixture'
EOF

cat > "$FAKE_BIN/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF
chmod 700 "$FAKE_BIN/docker" "$FAKE_BIN/ss" "$FAKE_BIN/curl" "$FAKE_BIN/free" "$FAKE_BIN/df" "$FAKE_BIN/sleep"

backup_name='novel_plus-20000101-000000.sql.gz'
printf '%s\n' 'CREATE TABLE runtime_probe (id bigint);' | /bin/gzip -c > "$REPO/deploy/backups/$backup_name"
digest="$(/bin/busybox sha256sum "$REPO/deploy/backups/$backup_name" | awk '{print $1}')"
printf '%s  %s\n' "$digest" "$backup_name" > "$REPO/deploy/backups/$backup_name.sha256"

export PATH="$FAKE_BIN:$PATH"
export SWAP_SAMPLE_SECONDS=0
export FAKE_BACKUP_CALLED="$TEST_ROOT/backup-called"

default_output="$($REPO/performance/check-production-deployment.sh)"
printf '%s' "$default_output" | grep -q 'runtime verification passed' || { echo 'Default runtime verification did not pass.' >&2; exit 1; }
[ ! -e "$FAKE_BACKUP_CALLED" ] || { echo 'Read-only default unexpectedly created a backup.' >&2; exit 1; }

allow_output="$($REPO/performance/check-production-deployment.sh --allow-backup)"
printf '%s' "$allow_output" | grep -q 'runtime verification passed' || { echo 'Backup-enabled runtime verification did not pass.' >&2; exit 1; }
[ -f "$FAKE_BACKUP_CALLED" ] || { echo 'Explicit backup mode did not call the backup script.' >&2; exit 1; }

FAKE_BAD_SOCKET=true
export FAKE_BAD_SOCKET
if "$REPO/performance/check-production-deployment.sh" >/dev/null 2>&1; then
  echo 'Runtime verification accepted an unexpected public MySQL socket.' >&2
  exit 1
fi
unset FAKE_BAD_SOCKET

FAKE_LAG=7
export FAKE_LAG
lag_output="$($REPO/performance/check-production-deployment.sh)"
printf '%s' "$lag_output" | grep -q 'WARN: Kafka consumer lag is nonzero' || {
  echo 'Runtime verification did not explicitly report nonzero consumer lag.' >&2
  exit 1
}

printf '%s\n' 'Production runtime checker behavior passed.'
